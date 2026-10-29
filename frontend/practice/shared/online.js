/** Small V2 boundary; a missing slice never turns startup into a failed page. */
export function unavailable(status) {
  return Object.assign(new Error(status===501?'该功能尚未开放。':'该功能尚未开放，或内容已不存在。'),
    {code:'FEATURE_NOT_AVAILABLE',status});
}

export async function loadLibraryIndex(request) {
  let value;
  try { value=await request('/api/library/index'); }
  catch(error) {
    if(error.code!=='FEATURE_NOT_AVAILABLE')throw error;
    try { value=await request('/api/catalog'); }
    catch(fallback) {
      if(fallback.code!=='FEATURE_NOT_AVAILABLE')throw fallback;
      return {banks:[],extensions:[],groups:{banks:[],extensions:[]},canManage:true,developmentEnabled:false,unavailable:true};
    }
  }
  return {...value,banks:(value.banks||[]).filter(row=>!row.development&&(!row.kind||row.kind==='bank')),extensions:[],groups:{banks:value.groups?.banks||[],extensions:[]},
    canManage:value.canManage!==false,developmentEnabled:false,unavailable:false};
}
