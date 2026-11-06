"""Exercise the actual local album form in RU/EN, including validation and network errors."""
import subprocess
script=r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/assets/icloud/setup.html','utf8');const code=html.replace('{{UPLOAD_PATH}}','/session/upload').match(/<script nonce="\{\{NONCE\}\}">([\s\S]*?)<\/script>/)[1];
async function run(lang,result){const nodes=new Map(),requests=[];function node(id){if(!nodes.has(id))nodes.set(id,{textContent:'',disabled:false,hidden:false,value:'  https://www.icloud.com/sharedalbum/#A1ExampleAlbum  ',addEventListener(type,callback){this[type]=callback}});return nodes.get(id)}
const context=vm.createContext({document:{documentElement:{lang},getElementById:node},fetch:async(url,options)=>{requests.push({url,options});if(result==='network')throw new Error('network');return {ok:result==='ok'}}});vm.runInContext(code,context);assert(node('title').textContent.includes(lang==='ru'?'iCloud':'iCloud'));let prevented=false;await node('form').submit({preventDefault(){prevented=true}});assert(prevented);assert.equal(requests.length,1);assert.equal(requests[0].url,'/session/upload');assert.equal(requests[0].options.headers['Content-Type'],'text/plain;charset=UTF-8');assert.equal(requests[0].options.body,'https://www.icloud.com/sharedalbum/#A1ExampleAlbum');if(result==='ok'){assert(node('form').hidden);assert(node('save').disabled)}else{assert(!node('form').hidden);assert(!node('save').disabled);assert(node('status').textContent.length>20)} }
(async()=>{for(const lang of ['ru','en'])for(const result of ['ok','invalid','network'])await run(lang,result);console.log('PASS: RU/EN album form, trimmed link, bounded text request, success and recoverable errors')})().catch(error=>{console.error(error);process.exitCode=1});
'''
subprocess.run(['node','-e',script],check=True)
