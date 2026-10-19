let csrf='';
export function setCsrf(value:string){csrf=value;}
export async function apiFetch(path:string,init:RequestInit={}){
 const headers=new Headers(init.headers);
 if(init.method&&init.method!=='GET'){headers.set('Content-Type','application/json');if(csrf)headers.set('X-CSRF-Token',csrf);}
 const response=await fetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'});
 if(response.status===401&&path!=='/api/auth')window.dispatchEvent(new Event('floatclip-auth-lost'));
 return response;
}
