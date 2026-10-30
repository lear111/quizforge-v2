import {rm,copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';

const root=fileURLToPath(new URL('..',import.meta.url));
// Upstream release scripts reject rebuilding published artifacts. Only these
// ignored, generated files are removed; component source and CSS stay intact.
for(const [version,script] of [['1.0.0','build-richtext.mjs'],['1.1.3','build-richtext-advanced.mjs']]) {
  for(const file of ['richtext.js','richtext-editor.js','THIRD_PARTY_NOTICES.md']) {
    await rm(resolve(root,'shared/richtext',version,file),{force:true});
  }
  execFileSync(process.execPath,[resolve(root,'scripts/build',script),'--version',version],{cwd:root,stdio:'inherit'});
}
await copyFile(resolve(root,'node_modules/html2canvas/dist/html2canvas.min.js'),resolve(root,'practice/assets/html2canvas-1.4.1.min.js'));
await import('./build-sdk.mjs');
