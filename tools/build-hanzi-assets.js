// Builds the Hanzi Lens dictionary assets from the IINA plugin's data files.
//   node tools/build-hanzi-assets.js <dir with words.js and chars.js> <assets/hanzi dir>
// words.bin: simp \t trad \t pinyin(numeric) \t defs(/-separated) \t freq \t hsk
// chars.bin: char \t first Mandarin reading (marked) \t short definition
const fs = require("fs"), vm = require("vm"), path = require("path"), zlib = require("zlib");
const [, , src, out] = process.argv;
const ctx = {}; ctx.window = ctx; vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(src, "words.js"), "utf8"), ctx);
vm.runInContext(fs.readFileSync(path.join(src, "chars.js"), "utf8"), ctx);
const words = ctx.HL_WORDS.split("\n").filter(Boolean).map((l) => {
  const c = l.split("\t");
  return [c[1], c[0], c[2], c[3], c[4] || "0", c[5] || "0"].join("\t");
});
fs.writeFileSync(path.join(out, "words.bin"), zlib.gzipSync(words.join("\n") + "\n", { level: 9 }));
const chars = Object.entries(ctx.HL_CHARS).map(([ch, i]) => [ch, (i.um || "").split(" ")[0], (i.md || i.ud || "").replace(/[\t\n]/g, " ")].join("\t"));
fs.writeFileSync(path.join(out, "chars.bin"), zlib.gzipSync(chars.join("\n") + "\n", { level: 9 }));
console.log(words.length, "words,", chars.length, "chars");
