"""Run the shipped phone page's picker/camera/crop/upload handlers against a small DOM adapter."""
from pathlib import Path
import subprocess
script=r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/assets/avatar/upload.html','utf8');const js=html.replace('{{UPLOAD_PATH}}','/secret/upload').match(/<script nonce="\{\{NONCE\}\}">([\s\S]*?)<\/script>/)[1];
const elements=new Map();let opened=0,revoked=0,requests=[],draws=[];
function node(id){if(!elements.has(id))elements.set(id,{textContent:'',style:{},value:'1',disabled:false,files:[],click(){opened++},setPointerCapture(){},getBoundingClientRect(){return {width:360}},getContext(){return {fillRect(){},drawImage(...args){draws.push(args)}}},toBlob(callback,type){assert.equal(type,'image/jpeg');callback(new Blob(['avatar'],{type}))}});return elements.get(id)}
class Photo {constructor(){this.naturalWidth=1024;this.naturalHeight=512}set src(v){this.onload()}}
const context=vm.createContext({document:{documentElement:{lang:'ru'},getElementById:node},Image:Photo,URL:{createObjectURL(){return 'blob:'+Math.random()},revokeObjectURL(){revoked++}},location:{pathname:'/secret/'},Blob,fetch:async(url,options)=>{requests.push({url,options});return {ok:true}}});
vm.runInContext(js,context);node('library').onclick();node('camera').onclick();assert.equal(opened,2);
node('file').onchange({target:{files:[{size:10,type:'application/zip'}],value:''}});assert(node('message').textContent);assert.equal(draws.length,0);
node('file').onchange({target:{files:[{size:2097153,type:'image/jpeg'}],value:''}});assert.equal(draws.length,0);assert(!node('message').textContent.includes('2'));
node('file').onchange({target:{files:[{size:1000,type:'image/jpeg'}],value:''}});assert.equal(node('crop').style.display,'block');assert.equal(node('pick').style.display,'none');assert.equal(draws.at(-1)[3],1024);assert.equal(draws.at(-1)[4],512);
node('zoom').oninput({target:{value:'2'}});assert.equal(draws.at(-1)[3],2048);node('preview').onpointerdown({pointerId:1,clientX:0,clientY:0});node('preview').onpointermove({clientX:9999,clientY:9999});node('preview').onpointerup();let last=draws.at(-1);assert(last[1]<=0&&last[2]<=0&&last[1]+last[3]>=512&&last[2]+last[4]>=512);
node('cancel').onclick();assert.equal(node('crop').style.display,'none');assert.equal(revoked,1);
node('shot').onchange({target:{files:[{size:1000,type:'image/jpeg'}],value:''}});node('save').onclick();
setImmediate(()=>{assert.equal(requests.length,1);assert.equal(requests[0].url,'/secret/upload');assert.equal(requests[0].options.headers['Content-Type'],'image/jpeg');assert.equal(node('done').style.display,'block');assert.equal(revoked,2);console.log('PASS: phone library/camera, invisible size/type rejection, bounded crop/pan/zoom, JPEG upload and success UI');});
'''
subprocess.run(['node','-e',script],check=True)
