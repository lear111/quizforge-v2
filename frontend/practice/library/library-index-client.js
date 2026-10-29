/** Delivers metadata only; opening collections and preserving editor state belong to the app. */
export function createLibraryIndexClient({request,onIndex,onError=()=>{}}){
  let generation=0,stopped=false;
  async function load(current){
    try{
      const value=await request('/api/library/index');
      if(stopped||current!==generation)return;
      onIndex(value);return value;
    }catch(error){
      if(stopped||current!==generation)return;
      onError(error);
      throw error;
    }
  }
  return {
    refresh(){if(stopped)return Promise.resolve();return load(++generation);},
    visibilityChanged(){},
    stop(){stopped=true;generation++;},
  };
}
