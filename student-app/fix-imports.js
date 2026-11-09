const fs = require('fs');
const path = require('path');

function walk(dir) {
  let results = [];
  const list = fs.readdirSync(dir);
  list.forEach(file => {
    const filePath = path.join(dir, file);
    const stat = fs.statSync(filePath);
    if (stat && stat.isDirectory()) {
      results = results.concat(walk(filePath));
    } else if (file.endsWith('.vue')) {
      results.push(filePath);
    }
  });
  return results;
}

const files = walk('src/pages');
files.forEach(f => {
  let content = fs.readFileSync(f, 'utf8');
  if (content.includes(".js'")) {
    content = content.replace(/\.js'/g, "'");
    fs.writeFileSync(f, content, 'utf8');
    console.log('Updated:', f);
  }
});
