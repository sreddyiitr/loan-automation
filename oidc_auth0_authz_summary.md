# Sign-in and permissions, explained simply

How the funder API knows **who** is calling (authentication, through OIDC) and **what** they may
do (authorization), told as a school story. It covers spec 002 (`FR-09`, `FR-11`, `D-13`,
`AS-11.2`), and the code in `funding-api/src/main/java/com/funding/api/auth/`.

**Status (2026-11-21):** steps 1–4 and 6 are built (T101–T102). Step 5, the permission rules, is
the next slice (D2).

---

## The people in the story

| In the school | In our app |
|---|---|
| **The front office** that makes ID cards | The **OIDC provider**. Locally that's `mock-oauth2-server`, the container on port 8090 (`scripts/oidc-env.sh`). In production it would be a real provider such as Auth0, Okta or Entra ID. |
| **The ID card** | The **bearer token** (a JWT) |
| **The classroom door** where the teacher checks cards | The **funder API** (our Java app), using Spring Security |
| **The class list** on the teacher's desk ("Sam is a hall monitor") | The **user directory**: `funding.auth.users` in `funding-api/src/main/resources/application.yml` |
| **The school rules** ("hall monitors may open the supply closet") | The **permissions table**: AS-11.2 in `specs/002-funder-dashboard/spec.md` |

---

## Step 1: Getting your ID card (signing in)

You walk up to the front office and say "I'm **funder-1**."

The office makes you an ID card. Written on it:
- **Who you are:** `funder-1`. The field is called `sub`, short for "subject".
- **Which office made it:** `http://127.0.0.1:8090/funding`. This is `iss`, the issuer.
- **Which door it's for:** `funding-api`. This is `aud`, the audience.
- **When it stops working:** in one hour. This is `exp`.

Then the office **stamps it with a special seal** that only that office can make. That seal is
the **signature**.

A real card from our local provider, opened up:

```
sub: funder-1   iss: http://127.0.0.1:8090/funding   aud: funding-api   exp: …
```

There are two ways to ask for a card:
- **In the browser** (coming in T105): the dashboard sends you to the provider's login page. You
  type `funder-1`, and you're sent back with the card. This is OIDC's "Authorization Code with
  PKCE" flow.
- **Without a browser** (tests and `curl`): one request that says "a card for
  `client_id=funder-1`, please". README step 6 does this:

  ```bash
  TOKEN=$(curl -s -X POST http://127.0.0.1:8090/funding/token \
    -d grant_type=client_credentials -d client_id=funder-1 -d client_secret=any -d scope=funding-api \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
  ```

⚠️ **Our practice front office believes anyone.** There's no password: whatever name you type
goes on the card. That's fine on your laptop, and it's exactly why it's local only. A real office
(Auth0, Okta, Entra ID) would check a password, and often a second factor, first.

---

## Step 2: Showing your card at the door (the bearer token)

Every time the dashboard (or `curl`) asks the API for something, it holds up the card:

```
Authorization: Bearer eyJraWQiOiJmdW5kaW5nIi…
```

"Bearer" just means "whoever *bears* (holds) this card gets in". So a card is like cash: if
someone steals it, they can use it. That's why it expires after an hour, and why the dashboard
will keep it in memory, never saved on disk.

---

## Step 3: How the backend knows who you are, from just a token

**The card itself says who you are.** The token isn't a secret code to look up somewhere; it's
like a sealed envelope you can read. It has three parts, separated by dots:

```
eyJraWQi…  .  eyJzdWIiOiJmdW5kZXItMSIs…  .  kX9f2…
  header          the writing on the card          the seal
                  (sub = funder-1, iss, aud, exp)
```

Anyone can *read* the middle part. So how does the teacher know you didn't just write "funder-1"
on a card yourself?

**The teacher checks the seal.** The office publishes a page that says "here's how to check our
seal": its **public key**, at `http://127.0.0.1:8090/funding/jwks`. Spring Security fetches it
once, then checks every card:

1. **Is the seal real?** Only the real office can make a seal that matches its public key. A
   forged or edited card fails. → **401**
2. **Is it from *our* office?** A card from some other office fails. → **401**
3. **Is it for *this* door?** A card for `some-other-api` fails. → **401**
4. **Is it still valid?** An expired card fails. → **401**

If all four pass, the backend trusts what's written on the card. `sub` becomes the **actor**, the
name written into the audit trail.

The functional test `AS_10_9_realTokensFromTheMockProvider` (in `FunderActionsE2EIT`) tries each
of these bad cards against the real provider.

The old way was worse: anyone could write `X-Funder-Id: supervisor-1` on a sticky note and walk
in. Now there's a seal.

> **Where this lives:** `SecurityConfig.java` turns on Spring Security's OAuth2 Resource Server.
> `application.yml` tells it which office and which door:
> `spring.security.oauth2.resourceserver.jwt.issuer-uri` and `audiences: funding-api`.

---

## Step 4: How the backend knows your role

The card says **who** you are, but **not** your role. Our practice office doesn't write roles on
cards.

So the teacher looks at **the class list on the desk**. That's the user directory in
`funding-api/src/main/resources/application.yml`:

```yaml
funding:
  auth.users:
    funder-1: FUNDER
    funder-2: FUNDER
    income-1: INCOME_SPECIALIST
    supervisor-1: SUPERVISOR
```

When your card is checked, `UserDirectory` looks up your name:
- **`funder-1` is on the list** → "you're a FUNDER". Spring Security attaches the label
  `ROLE_FUNDER` to your request.
- **`stranger` isn't on the list** → no role → **403 `UNKNOWN_USER`**. Your card is real, but
  you're not in this class.

Two different "no"s:
- **401** = "I can't trust your card."
- **403** = "Your card is fine, but you're not allowed."

### How do you add a new user, or change a role?

**Today: edit that list and restart the app.** For example, to add an income specialist named
`priya`:

```yaml
    priya: INCOME_SPECIALIST
```

Nothing changes in the OIDC provider; it'll make a card for any name. The list in *our* app
decides who's allowed in and what they are.

**Later, with real single sign-on,** you wouldn't edit a file. The company's directory would say
"Priya is in the Income Specialists group"; with Auth0 or Okta this is a group or role on the
user, with Entra ID an app role. We'd point the user directory at that instead. That's a
configuration change, not a code change.

This split is decision **D-13** in the spec: the office says *who*, and our app decides *what*.

---

## Step 5: How the backend knows what each role may do

**This part isn't built yet. It's the next slice, D2.**

The rules are already written down, as the table in spec AS-11.2. In short:

| Action | Funder | Income specialist | Supervisor |
|---|---|---|---|
| Waive a normal stip, on their own deal | ✅ | ✅ | ✅ any deal |
| Waive an **income** stip | ❌ | ✅ | ✅ |
| Approve while income work is pending | ❌ | ✅ | ✅ |
| Reassign a deal to someone else | ❌ | ❌ | ✅ |
| … and so on (the full table is in the spec) | | | |

In D2 this becomes a small Java class, `Permissions`, which answers one question:

> "Can **this role** do **this action** on **this deal**?" → *yes*, or *no, because…*

The "because" is a reason like `NOT_ASSIGNED_TO_YOU` or `INCOME_WORK_NEEDS_SPECIALIST`. Before
every write, the API asks `Permissions` first, and refuses with 403 and that reason. The same
class also tells the dashboard which buttons to show, so the screen and the API can never disagree.

**What happens right now, before D2:** the door checks your card and checks that you're on the
class list, and that's it. Anyone on the list can do any action. The API already *knows* your
role, because it's inside the `Actor` that every write receives; it just doesn't use it yet.

---

## The whole trip, in one picture

```
 You: "I'm funder-1"
   │
   ▼
 ① OIDC provider (port 8090)  ── makes a sealed card: sub=funder-1, aud=funding-api, 1 hour
   │
   ▼
 ② Dashboard / curl  ── "Authorization: Bearer <card>" on every request
   │
   ▼
 ③ Funder API door (Spring Security)
     • seal real?  right office?  right door?  not expired?   no → 401 UNAUTHORIZED
     • read sub = "funder-1"
   │
   ▼
 ④ User directory (application.yml)
     • funder-1 → FUNDER                                       not listed → 403 UNKNOWN_USER
   │
   ▼
 ⑤ Permissions (D2, not built yet)
     • may a FUNDER approve this deal right now?               no → 403 + reason
   │
   ▼
 ⑥ Do the work, and write "actor = funder-1" in the audit trail
```

Steps ①–④ and ⑥ are built (T101–T102). Step ⑤ is D2.

---

## Words you'll see

| Word | Plain meaning |
|---|---|
| **Authentication** (authn) | Proving *who* you are: steps 1–3 |
| **Authorization** (authz) | Deciding *what* you may do: steps 4–5 |
| **OIDC** (OpenID Connect) | The standard way for an office to sign you in and hand out cards. Auth0, Okta, Entra ID and our mock provider all speak it. |
| **OAuth 2.0** | The standard OIDC is built on; it defines tokens and how to ask for them |
| **JWT** (JSON Web Token) | The card's format: three dot-separated parts (header, claims, signature) |
| **Claim** | One line written on the card: `sub`, `iss`, `aud`, `exp` |
| **JWKS** | The office's published public keys, used to check the seal |
| **Bearer token** | A card that works for whoever holds it, sent as `Authorization: Bearer …` |
| **Resource Server** | An API that accepts cards: our funder API |
| **PKCE** | A safety step in browser sign-in, so a stolen login code can't be swapped for a card |
| **`client_credentials`** | Asking for a card with no browser. We use it for tests and `curl`. |
