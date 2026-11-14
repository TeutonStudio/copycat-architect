"""Exercise references and viewport pagination against the running Graphstudio."""
import argparse, json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
parser=argparse.ArgumentParser()
parser.add_argument('--url',default='http://127.0.0.1:4193/')
parser.add_argument('--chromium',required=True)
parser.add_argument('--artifacts',type=Path,default=Path('/tmp/graphstudio-reference-browser'))
a=parser.parse_args();a.artifacts.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 b=p.chromium.launch(executable_path=a.chromium,headless=True,args=['--no-sandbox','--enable-unsafe-swiftshader'])
 page=b.new_page(viewport={'width':1440,'height':900});page.set_default_timeout(20000)
 errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto(a.url+'?editor=building',wait_until='domcontentloaded')
 studio=page.locator('.studio:not([hidden])')
 def fit(label):
  page.wait_for_timeout(180)
  failures=page.evaluate("""() => {
   const failures=[];
   if(document.documentElement.scrollWidth>innerWidth||document.documentElement.scrollHeight>innerHeight) failures.push('document scrolls');
   for(const e of document.querySelectorAll('button,input:not([type=hidden]),select,summary,.page-controls,.pager-body [data-page-item],.copycat-card')) {
    if(!e.getClientRects().length||e.closest('[hidden],.pager-hidden,.pager-empty'))continue;
    const r=e.getBoundingClientRect();if(r.width<1||r.height<1)continue;
    if(r.x<-.5||r.y<-.5||r.right>innerWidth+.5||r.bottom>innerHeight+.5) failures.push([e.className,e.textContent.slice(0,35),r.toJSON()]);
    const panel=e.closest('.pager-body,.copycat-results');if(panel) {const q=panel.getBoundingClientRect();if(r.top<q.top-1||r.bottom>q.bottom+1||r.left<q.left-1||r.right>q.right+1)failures.push(['clipped',e.textContent.slice(0,35),r.toJSON(),q.toJSON()]);}
   }return failures;
  }""")
  if failures:page.screenshot(path=str(a.artifacts/(label+'-failure.png')))
  assert not failures,(label,failures[:8])
 def pages(panel,label):
  for i in range(100):
   fit(label+str(i));n=panel.locator(':scope > .page-controls button').nth(1)
   if n.is_disabled():break
   n.click()
  else:raise AssertionError('unbounded pagination '+label)
  first=panel.locator(':scope > .page-controls button').first
  while not first.is_disabled():first.click();page.wait_for_timeout(50)
 studio.locator(':scope > .studio-navigation > button').click()
 dialog=page.get_by_role('dialog',name='Referenz auswählen',exact=True)
 dialog.get_by_role('button',name='deutsche Renaisance Ordner öffnen').click()
 dialog.get_by_role('searchbox').fill('Kreuzstockfenster')
 dialog.get_by_role('button',name='Kreuzstockfenster · 3 × 5 · Architekturvorlage',exact=True).click()
 expect(dialog.get_by_role('button',name='Auswahl übernehmen')).to_be_enabled(timeout=30000)
 fit('picker-desktop');page.screenshot(path=str(a.artifacts/'picker-desktop.png'))
 for w,h in [(390,844),(844,390),(320,568)]:
  page.set_viewport_size({'width':w,'height':h});fit('picker-catalog-'+str(w))
  dialog.locator('.reference-view-tabs').get_by_role('button',name='Vorschau',exact=True).click();fit('picker-preview-'+str(w))
  page.screenshot(path=str(a.artifacts/('picker-preview-'+str(w)+'.png')))
  dialog.locator('.reference-view-tabs').get_by_role('button',name='Bibliothek',exact=True).click()
 dialog.get_by_role('button',name='Auswahl übernehmen').click()
 expect(dialog).to_have_count(0)
 expect(studio.locator('.react-flow__node').filter(has_text='Kreuzstockfenster')).to_have_count(1)
 for w,h in [(1920,1080),(1366,768),(1024,768),(768,1024),(390,844),(844,390),(320,568),(1024,576),(853,480),(640,360)]:
  page.set_viewport_size({'width':w,'height':h});fit('studio-'+str(w))
  tabs=studio.get_by_role('group',name='Arbeitsansicht')
  if tabs.is_visible():tabs.get_by_role('button',name='Eigenschaften',exact=True).click()
  pages(studio.locator('.inspector-body'),'inspector-'+str(w))
  if tabs.is_visible():tabs.get_by_role('button',name='Vorschau',exact=True).click()
  for panel in studio.locator('.preview-hud .paged-panel').all():pages(panel,'hud-'+str(w))
  if tabs.is_visible():tabs.get_by_role('button',name='Graph',exact=True).click()
  studio.locator('.studio-tools > summary').click();pages(studio.locator('.studio-tools > .paged-panel'),'tools-'+str(w));studio.locator('.studio-tools > .paged-panel > .page-controls').get_by_role('button',name='Schließen',exact=True).click()
  page.screenshot(path=str(a.artifacts/('studio-'+str(w)+'.png')))
 # Browser draft reopens as a single reference, with its embedded source version.
 page.reload(wait_until='domcontentloaded');expect(studio.locator('.react-flow__node').filter(has_text='Kreuzstockfenster')).to_have_count(1)
 page.get_by_role('button',name='Meine Bibliotheken',exact=True).click()
 pages(page.locator('.library-content'),'library')
 page.get_by_role('button',name='Schließen',exact=True).click()
 # The form editor shares the same viewport and still exposes native fields.
 page.get_by_role('button',name='Formgraph',exact=True).click()
 for w,h in [(1440,900),(390,844),(844,390),(320,568)]:
  page.set_viewport_size({'width':w,'height':h});fit('form-'+str(w))
  tabs=studio.get_by_role('group',name='Arbeitsansicht')
  if tabs.is_visible():tabs.get_by_role('button',name='Eigenschaften',exact=True).click()
  pages(studio.locator('.inspector-body'),'form-inspector-'+str(w))
  if tabs.is_visible():tabs.get_by_role('button',name='Graph',exact=True).click()
 assert not errors,errors
 print('PASS: form/building editors, template insertion, source preview, reload, all property/HUD/tool/library pages and viewport fit at ten sizes, including effective 125/150/200 percent zoom sizes')
 b.close()
