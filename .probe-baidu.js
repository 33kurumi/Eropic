
const fs = require('fs');
const path = 'D:/Github/Project/Eropic/app/src/main/res/drawable-nodpi/ic_launcher_user.png';
const buf = fs.readFileSync(path);
console.log('image bytes', buf.length);
async function attempt(name, extraHeaders, extraForm) {
  const fd = new FormData();
  fd.append('image', new Blob([buf], {type:'image/png'}), 'image.png');
  fd.append('from','pc');
  if (extraForm) for (const [k,v] of Object.entries(extraForm)) fd.append(k,v);
  try {
    const res = await fetch('https://graph.baidu.com/upload', {
      method:'POST', body: fd,
      headers: { 'User-Agent':'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36', 'Referer':'https://graph.baidu.com/', ...extraHeaders },
      signal: AbortSignal.timeout(20000),
    });
    const text = await res.text();
    console.log('---', name, 'status', res.status, 'len', text.length);
    console.log(text.slice(0, 600));
  } catch (e) { console.log('---', name, 'FAIL', e.name, e.message); }
}
(async()=>{
  await attempt('A_empty_acstoken', {'Acs-Token':''});
  await attempt('B_no_acstoken', {});
  await attempt('C_with_cookie_header_guess', {'Acs-Token':'', 'Cookie':'BAIDUID=00000000000000000000000000000000:FG=1'});
})();
