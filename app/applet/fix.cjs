const fs = require('fs');
const filepath = '../src/main/java/com/example/MainActivity.kt';
let content = fs.readFileSync(filepath, 'utf8');

// Replace the literal regex residue with the correct braces and comment
content = content.replace("                  \\}\\r\\?\\n      \\}\\r\\?\\n\\r\\?\\n      /\\*\\*", "                  }\n      }\n\n      /**");

fs.writeFileSync(filepath, content, 'utf8');
console.log("Success!");
