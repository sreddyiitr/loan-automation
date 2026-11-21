// Spike D0 / T002 step 1: render hand-written A2UI v1.0 messages with the published React renderer.
// No agent yet: the messages below are exactly what the agent will send later.
import {useEffect, useState} from 'react';
import {MessageProcessor} from '@a2ui/web_core/v1_0';
import {basicCatalog} from '@a2ui/web_core/catalogs/basic/v1';
import {A2uiSurface} from '@a2ui/react';

// A2UI v1.0: a flat component list (ids use underscores, UAX #31), one "root",
// values bound to the data model with {"@path": ...}.
const messages = [
  {version: '1.0', createSurface: {surfaceId: 'deal_overview', catalogId: basicCatalog.id}},
  {
    version: '1.0',
    updateComponents: {
      surfaceId: 'deal_overview',
      components: [
        {id: 'root', component: 'Card', child: 'body'},
        {id: 'body', component: 'Column', children: ['title', 'status', 'dealer', 'take_button']},
        {id: 'title', component: 'Text', text: {'@path': '/deal/loanApplicationId'}},
        {id: 'status', component: 'Text', text: {'@call': 'formatString', args: {value: 'Status: ${/deal/status}'}}},
        {id: 'dealer', component: 'Text', text: {'@path': '/deal/dealershipName'}},
        {id: 'take_label', component: 'Text', text: 'Take this deal'},
        {
          id: 'take_button',
          component: 'Button',
          variant: 'primary',
          child: 'take_label',
          action: {event: {name: 'deal.take', context: {loanApplicationId: {'@path': '/deal/loanApplicationId'}}}},
        },
      ],
    },
  },
  {
    version: '1.0',
    updateDataModel: {
      surfaceId: 'deal_overview',
      path: '/deal',
      value: {loanApplicationId: 'LA-1006', status: 'STIPULATED', dealershipName: 'Lone Star Motors'},
    },
  },
];

export default function App() {
  const [events, setEvents] = useState<string[]>([]);
  const [processor] = useState(() => {
    // The action handler is where the agent will receive clicks (A2UI renderer_to_agent "action").
    const p = new MessageProcessor([basicCatalog], (action: unknown) => {
      setEvents((e) => [...e, JSON.stringify(action)]);
    });
    p.processMessages(messages as never);
    return p;
  });
  const [surfaces, setSurfaces] = useState(() => Array.from(processor.model.surfacesMap.values()));
  useEffect(() => {
    const sync = () => setSurfaces(Array.from(processor.model.surfacesMap.values()));
    const created = processor.onSurfaceCreated(sync);
    const deleted = processor.onSurfaceDeleted(sync);
    return () => {
      created.unsubscribe();
      deleted.unsubscribe();
    };
  }, [processor]);

  return (
    <main style={{fontFamily: 'system-ui', maxWidth: 640, margin: '2rem auto'}}>
      <h1>A2UI spike</h1>
      {surfaces.map((s) => (
        <A2uiSurface key={s.id} surface={s as never} />
      ))}
      <h3>Events sent back to the agent</h3>
      <pre data-testid="events">{events.join('\n') || '(none yet)'}</pre>
    </main>
  );
}
