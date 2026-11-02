export function bindLibraryManagement({request,getContext,beforeChange,onChanged,onExport,onConflict}){
  const $=selector=>document.querySelector(selector),dialog=$('#library-manage-dialog'),form=$('#library-manage-form');
  let operation=null,subject=null,returnEdit=null,busy=false;
  const close=()=>{if(!busy)dialog.close();};
  const cancel=()=>{
    if(busy)return;
    if(returnEdit){const previous=returnEdit;open(previous.target,previous.row);$('#library-manage-name').value=previous.name;$('#library-manage-group').value=previous.group;}
    else close();
  };
  $('#library-manage-close').addEventListener('click',close);$('#library-manage-cancel').addEventListener('click',cancel);
  dialog.addEventListener('cancel',event=>{event.preventDefault();cancel();});
  const error=message=>{$('#library-manage-error').textContent=message;$('#library-manage-error').hidden=!message;};
  const status=message=>{$('#library-manage-status').textContent=message;$('#library-manage-status').hidden=!message;};
  const setBusy=value=>{busy=value;form.setAttribute('aria-busy',String(value));for(const control of form.querySelectorAll('button,input,select'))control.disabled=value;};
  function open(target,row,action='update'){
    const {kind,catalog}=getContext();if(busy||!catalog.canManage||kind==='extension'&&action==='create')return;
    operation={kind,target,action,path:row?.path||row?.id||'',revision:catalog.managementRevision};subject={...row};returnEdit=null;
    const type=target==='group'?(kind==='extension'?'拓展分组':'分组'):kind==='bank'?'题库':'拓展',removing=action==='delete',deletingBank=removing&&kind==='bank'&&target==='item',readOnly=kind==='extension'&&!removing;
    $('#library-manage-title').textContent=`${removing?'删除':action==='create'?'新建':readOnly?'管理':'编辑'}${type}`;
    $('#library-manage-name').maxLength=target==='group'?120:400;
    $('#library-manage-name').value=target==='group'?(row?.name||''):(row?.title||row?.name||'');
    $('#library-manage-fields').hidden=removing||readOnly;$('#library-manage-confirm').hidden=readOnly;$('#library-manage-confirm').textContent=removing?'确认删除':'保存';
    $('#library-manage-confirm').classList.toggle('danger-button',removing);
    const count=target==='group'?catalog.banks.filter(bank=>bank.group===row?.path).length:1;
    const hint=removing?`将删除「${row?.title||row?.name||row?.path}」${target==='group'?`中的 ${count} 本题库`:''}。作答、历史记录与笔记将一并删除，无法恢复。`:
      target==='group'?'分组是文件夹，可以暂时为空；改名不会重置作答与历史。':'仅修改显示名称或分组，不影响题库身份、作答与历史。';
    $('#library-manage-hint').textContent=hint;
    $('#library-manage-more').hidden=action!=='update';$('#library-manage-export').hidden=kind!=='bank'||!onExport;$('#library-manage-export').textContent=target==='group'?'导出分组':'导出题库';
    $('#library-manage-cancel').textContent=readOnly?'关闭':'取消';
    const select=$('#library-manage-group');select.closest('label').hidden=target==='group';$('#library-manage-cancel').hidden=false;select.replaceChildren(new Option('未分组（根目录）',''));
    for(const path of catalog.groups?.[kind==='bank'?'banks':'extensions']||[]){
      if(target==='group'||path.includes('/')||path.includes('\\'))continue;
      select.append(new Option(path,path));
    }
    select.value=target==='group'?'':row?.group||'';error('');status('');if(!dialog.open)dialog.showModal();
    (removing?$('#library-manage-cancel'):readOnly?$('#library-manage-cancel'):$('#library-manage-name')).focus();
  }
  $('#library-manage-delete').addEventListener('click',()=>{
    if(busy||operation?.action!=='update')return;
    const previous={target:operation.target,row:subject,name:$('#library-manage-name').value,group:$('#library-manage-group').value};
    open(previous.target,previous.row,'delete');returnEdit=previous;
  });
  $('#library-manage-export').addEventListener('click',async()=>{
    if(busy||operation?.action!=='update'||operation.kind!=='bank'||!onExport)return;
    setBusy(true);error('');status('正在导出已保存的内容…');
    try{if(await onExport(subject,operation.target)===false)throw new Error('正在导入或导出，请稍后重试。');status('已导出当前保存的内容。');}
    catch(failure){status('');error(failure.message);}
    finally{setBusy(false);}
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!operation||operation.kind==='extension'&&operation.action!=='delete')return;const value={...operation};
    if(value.action!=='delete'){value.name=$('#library-manage-name').value.trim();value.group=value.target==='group'?'':$('#library-manage-group').value;if(!value.name){error('请输入名称');return;}if(value.target==='item'&&[...value.name].length>200){error('题库名称最长 200 个字符');return;}}
    setBusy(true);error('');status('');
    try{await beforeChange(value);const result=await changeLibrary(request,value,subject);await onChanged(result,value);if(result.partial){operation.revision=getContext().catalog.managementRevision;status(result.message);returnEdit=null;}else dialog.close();}
    catch(failure){if(failure.code==='LIBRARY_CONFLICT'){await onConflict?.();operation.revision=getContext().catalog.managementRevision;}error(failure.message);}
    finally{setBusy(false);}
  });
  return {open};
}

export async function changeLibrary(request,operation,subject){
  if(operation.kind==='bank'&&operation.target==='item'&&operation.action==='delete'){
    await request(`/api/collections/bank/${encodeURIComponent(subject.id)}`,{method:'DELETE',timeoutMs:60000});
    return {deleted:true};
  }
  return request('/api/library/manage',{method:'POST',body:JSON.stringify(operation),timeoutMs:60000});
}
