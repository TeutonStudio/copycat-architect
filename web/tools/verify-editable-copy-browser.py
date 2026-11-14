"""Verify independent template copies, primitive editing, return/undo and reload."""
import argparse,json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:4194/');p.add_argument('--chromium',required=True);p.add_argument('--artifacts',type=Path,default=Path('/tmp/graphstudio-editable-copy'))
a=p.parse_args();a.artifacts.mkdir(parents=True,exist_ok=True)
with sync_playwright() as runtime:
 browser=runtime.chromium.launch(executable_path=a.chromium,headless=True,args=['--no-sandbox','--enable-unsafe-swiftshader'])
 page=browser.new_page(viewport={'width':1440,'height':900});page.set_default_timeout(20000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto(a.url+'?editor=building',wait_until='domcontentloaded');studio=page.locator('.studio:not([hidden])')
 def snapshot():
  expect(page.locator('.workspace-status')).to_contain_text('Im Browser gespeichert')
  return page.evaluate("""()=>new Promise((resolve,reject)=>{const r=indexedDB.open('copycat-architect-graphstudio',1);r.onsuccess=()=>{const q=r.result.transaction('workspace').objectStore('workspace').get('current');q.onsuccess=()=>{resolve(q.result);r.result.close()};q.onerror=reject};r.onerror=reject})""")
 def find_control(panel,control):
  for _ in range(100):
   if control.is_visible():return control
   back=panel.locator(':scope > .page-controls button').first
   if not back.is_disabled():back.click()
   else:break
  for _ in range(100):
   if control.is_visible():return control
   forward=panel.locator(':scope > .page-controls button').nth(1)
   if forward.is_disabled():break
   forward.click()
  raise AssertionError('Control not reachable: '+str(control))
 def inspector():
  tabs=studio.get_by_role('group',name='Arbeitsansicht')
  if tabs.is_visible():tabs.get_by_role('button',name='Eigenschaften',exact=True).click()
  return studio.locator('.inspector-body')
 def source_copy(nodeid):
  tabs=studio.get_by_role('group',name='Arbeitsansicht')
  if tabs.is_visible():tabs.get_by_role('button',name='Graph',exact=True).click()
  studio.locator('.react-flow__node[data-id="'+nodeid+'"]').click()
  panel=inspector();find_control(panel,panel.get_by_role('button',name='Als Kopie im Graph öffnen/bearbeiten',exact=True)).click()
 def fit():
  page.wait_for_timeout(150)
  failures=page.evaluate("""()=>[...document.querySelectorAll('button,input:not([type=hidden]),select,summary')].filter(e=>e.getClientRects().length&&!e.closest('[hidden],.pager-hidden,.pager-empty')).flatMap(e=>{const r=e.getBoundingClientRect();const p=e.closest('.pager-body');const q=p?.getBoundingClientRect();return r.left<-.5||r.top<-.5||r.right>innerWidth+.5||r.bottom>innerHeight+.5||q&&(r.top<q.top-1||r.bottom>q.bottom+1||r.right>q.right+1)?[[e.textContent.slice(0,50),r.toJSON()]]:[]})""")
  assert not failures,failures
 def choose(name):
  studio.locator(':scope > .studio-navigation > button').first.click()
  dialog=page.get_by_role('dialog',name='Referenz auswählen',exact=True)
  dialog.get_by_role('searchbox').fill(name)
  dialog.get_by_role('button',name=name+' · Architekturvorlage',exact=True).click()
  return dialog
 dialog=choose('Kreuzstockfenster · 3 × 5')
 for w,h in [(1440,900),(390,844),(844,390),(320,568)]:
  page.set_viewport_size({'width':w,'height':h});fit()
 page.set_viewport_size({'width':1440,'height':900})
 dialog.get_by_role('button',name='Als Kopie im Graph öffnen',exact=True).click();expect(dialog).to_have_count(0)
 original=snapshot()['drafts']['building'];assert 'Kopie' in original['document']['name']
 sources=[n for n in original['document']['graph']['nodes'] if n['kind']=='COPYCAT'];assert sources
 assert any(n['kind']=='SEGMENT_LAYOUT' for n in original['document']['graph']['nodes'])
 source=next(n for n in sources if 'Kreuzstockverglasung' in n['name']);oldform=original['library']['forms'][source['form']]
 source_copy(source['id']);child=snapshot()['drafts']['form']
 assert any(n['kind']=='INPUT' for n in child['document']['graph']['nodes']);assert child['copyReturn']['nodeId']==source['id']
 for w,h in [(1440,900),(390,844),(844,390),(320,568),(640,360)]:
  page.set_viewport_size({'width':w,'height':h});fit()
 page.set_viewport_size({'width':1440,'height':900})
 primitive=next(n for n in child['document']['graph']['nodes'] if n['kind']=='INPUT' and n['part']['source']=='copycats:copycat_layer' and 1<n['part']['size']<8)
 studio.locator('.react-flow__node[data-id="'+primitive['id']+'"]').click();panel=inspector()
 size=find_control(panel,panel.get_by_label('Größe',exact=True));size.fill(str(primitive['part']['size']-1));size.press('Tab')
 changed=snapshot()['drafts']['form'];assert next(n for n in changed['document']['graph']['nodes'] if n['id']==primitive['id'])['part']['size']==primitive['part']['size']-1
 page.screenshot(path=str(a.artifacts/'editable-form.png'))
 page.reload(wait_until='domcontentloaded');expect(studio.get_by_role('button',name='In Bauteilkopie übernehmen',exact=True)).to_be_visible()
 studio.get_by_role('button',name='In Bauteilkopie übernehmen',exact=True).click()
 applied=snapshot()['drafts']['building'];updated=next(n for n in applied['document']['graph']['nodes'] if n['id']==source['id'])
 assert updated['form']!=source['form'];assert applied['library']['forms'][source['form']]==oldform
 assert applied['library']['forms'][updated['form']]['graph']!=oldform['graph']
 page.screenshot(path=str(a.artifacts/'edited-window.png'))
 # Undo restores the previous reference and library snapshot together.
 studio.locator('.studio-tools > summary').click();tools=studio.locator('.studio-tools > .paged-panel')
 find_control(tools,tools.get_by_role('button',name='Rückgängig',exact=True)).click()
 tools.locator(':scope > .page-controls').get_by_role('button',name='Schließen',exact=True).click()
 undone=snapshot()['drafts']['building'];assert next(n for n in undone['document']['graph']['nodes'] if n['id']==source['id'])['form']==source['form']
 source_copy(source['id']);studio.get_by_role('button',name='Zurück ohne Übernahme',exact=True).click()
 cancelled=snapshot()['drafts']['building'];assert cancelled['document']==undone['document']
 # Opening another copy keeps the previous edited draft in the browser library.
 dialog=choose('Kreuzstockfenster · 3 × 5');dialog.get_by_role('button',name='Als Kopie im Graph öffnen',exact=True).click()
 reopened=snapshot();assert reopened['drafts']['building']['document']['name']!=original['document']['name']
 assert any(g['name'].endswith('Entwurf vor Kopie') and g['document']==undone['document'] for g in reopened['graphs'])
 # Complex profiles expose smaller recipes that themselves remain editable.
 studio.locator(':scope > .studio-navigation > button').first.click();dialog=page.locator('dialog');dialog.get_by_role('searchbox').fill('Diamantquader');dialog.get_by_role('button',name='Diamantquader · Meine gespeicherten Formen',exact=True).first.click();dialog.get_by_role('button',name='Als Kopie im Graph öffnen',exact=True).click()
 complex=snapshot()['drafts']['form'];nested=next(n for n in complex['document']['graph']['nodes'] if n['kind']=='SAVED')
 source_copy(nested['id']);expect(studio.get_by_role('button',name='In Formkopie übernehmen',exact=True)).to_be_visible()
 studio.get_by_role('button',name='In Formkopie übernehmen',exact=True).click();assert snapshot()['drafts']['form']['document']['name']==complex['document']['name']
 assert not errors,errors
 print('PASS: template copy, primitive editing, source preservation, apply/undo, cancel, draft backup, reload, nested recipes and mobile fit')
 browser.close()
