
const fs = require('fs');
const buf = fs.readFileSync('D:/Github/Project/Eropic/app/src/main/res/drawable-nodpi/ic_launcher_user.png');
async function attempt(name, fileBuf, filename, type) {
  const fd = new FormData();
  fd.append('image', new Blob([fileBuf], {type}), filename);
  fd.append('from','pc');
  try {
    const res = await fetch('https://graph.baidu.com/upload', { method:'POST', body: fd,
      headers: { 'User-Agent':'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36', 'Referer':'https://graph.baidu.com/', 'Acs-Token':'' },
      signal: AbortSignal.timeout(25000) });
    const text = await res.text();
    console.log('---', name, res.status, text.slice(0,200));
  } catch(e){ console.log('---', name, 'FAIL', e.name, e.message); }
}
(async()=>{
  await attempt('png_as_jpg', buf, 'image.jpg', 'image/jpeg');
  await attempt('png_as_png', buf, 'image.png', 'image/png');
  const big = Buffer.concat([buf, Buffer.alloc(6*1024*1024, 7)]);
  await attempt('7MB_broken_image', big, 'image.jpg', 'image/jpeg');
  await attempt('empty', Buffer.alloc(0), 'image.jpg', 'image/jpeg');
})();
