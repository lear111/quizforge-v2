// Folders organize independent installed extensions. A group is never a
// runnable collection and carries no extension identity or combined samples.
export function extensionGroups(rows,folders=[]){
  const roots=[],groups=new Map();
  const ensure=folder=>{
    const path=typeof folder==='string'?folder:'';
    if(!path)return roots;
    let group=groups.get(path);
    if(!group){group={kind:'group',name:path,path,children:[]};groups.set(path,group);roots.push(group);}
    return group.children;
  };
  for(const row of rows)ensure(row.group).push({kind:'extension',row});
  for(const folder of folders)ensure(folder);
  return roots;
}
