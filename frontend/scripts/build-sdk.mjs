import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {answerAreaModel} from '../shared/answer-area/1.4.0/model.js';
import {createAnswerAreaService,ANSWER_AREA_STYLE} from '../shared/answer-area/1.2.0/component.js';
import {createSharedAnswerAreaService,ANSWER_AREA_STYLE as SHARED_STYLE} from '../shared/answer-area/1.4.1/component.js';
import {paintInkStroke} from '../shared/ink/1.0.0/renderer.js';

const root=new URL('../',import.meta.url),output=new URL('.generated-sdk/',root);
async function emit(id,version,value){const directory=new URL(id+'/',output);await mkdir(directory,{recursive:true});await writeFile(new URL(version+'.json',directory),JSON.stringify(value));}
for(const version of ['1.0.0','1.1.3']){
  const directory=new URL('shared/richtext/'+version+'/',root);
  const style=await readFile(new URL('richtext.css',directory),'utf8');
  const script=await readFile(new URL('richtext.js',directory),'utf8');
  const editorScript=await readFile(new URL('richtext-editor.js',directory),'utf8');
  await emit('quizforge.richtext',version,{style,script});
  await emit('quizforge.richtext',version+'-editor',{style,script:editorScript});
}
// Provider assembly follows quizforge_web@4522bb8 core/server/host/extensions/answer-area-component.js.
// Only the versioned fork sources above are used; no upstream working-tree source is evaluated.
await emit('quizforge.answer-area','1.4.1',{
  version:'1.4.1',style:ANSWER_AREA_STYLE+'\n'+SHARED_STYLE,
  script:`globalThis.__qfAnswerAreaProvider=Object.freeze({version:'1.4.1',model:(${answerAreaModel.toString()})(${paintInkStroke.toString()}),create:options=>{const legacy=(${createAnswerAreaService.toString()})(options),shared=(${createSharedAnswerAreaService.toString()})(options);return {api:Object.freeze({version:'1.4.1',formatVersion:1,mount:(container,config)=>config?.sharedInk===true?shared.api.mount(container,config):legacy.api.mount(container,config)}),setInk:value=>shared.setInk(value),setTools:value=>legacy.setTools(value),command:value=>['select-image','deactivate-image'].includes(value?.action)?shared.command(value):legacy.command(value),async flush(){await shared.flush();await legacy.flush();},refresh(){shared.refresh();legacy.refresh();},setActive(value){shared.setActive(value);legacy.setActive(value);},destroy(){shared.destroy();legacy.destroy();}};}});`
});
console.log('Built authenticated SDK JSON in '+fileURLToPath(output));
