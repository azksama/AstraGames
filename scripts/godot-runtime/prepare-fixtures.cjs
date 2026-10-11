// Synthetic, redistributable fixtures for the optional Android Godot runtime tests.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const output = path.resolve(__dirname, '../../build/godot-validation/fixtures');
const aligned = (value, alignment) => Math.ceil(value / alignment) * alignment;

function createPack(generation, files) {
  const header = Buffer.alloc(generation === 3 ? 88 : 100);
  header.write('GDPC');
  header.writeUInt32LE(generation === 3 ? 1 : 2, 4);
  header.writeUInt32LE(generation, 8);
  header.writeUInt32LE(generation === 3 ? 6 : 3, 12);
  const entries = Object.entries(files).map(([name, text]) => ({
    name: Buffer.from(`res://${name}`), data: Buffer.from(text)
  }));
  header.writeUInt32LE(entries.length, header.length - 4);
  const indexSize = entries.reduce((size, entry) => size + 4 + aligned(entry.name.length, 4) + 32 + (generation === 4 ? 4 : 0), 0);
  let offset = aligned(header.length + indexSize, 16);
  const index = Buffer.alloc(indexSize);
  let cursor = 0;
  const payload = [];
  for (const entry of entries) {
    const length = aligned(entry.name.length, 4);
    index.writeUInt32LE(length, cursor); cursor += 4;
    entry.name.copy(index, cursor); cursor += length;
    index.writeBigUInt64LE(BigInt(offset), cursor); cursor += 8;
    index.writeBigUInt64LE(BigInt(entry.data.length), cursor); cursor += 8;
    crypto.createHash('md5').update(entry.data).digest().copy(index, cursor); cursor += 16;
    if (generation === 4) cursor += 4;
    payload.push(entry.data);
    offset += entry.data.length;
  }
  return Buffer.concat([header, index, Buffer.alloc(aligned(header.length + indexSize, 16) - header.length - indexSize), ...payload]);
}

for (const generation of [3, 4]) {
  const files = {
    'project.godot': `config_version=${generation === 3 ? 4 : 5}\n\n[application]\nconfig/name="Astra Godot ${generation} validation"\nrun/main_scene="res://Main.tscn"\n\n[rendering]\n${generation === 3 ? 'quality/driver/driver_name="GLES2"' : 'renderer/rendering_method="gl_compatibility"\nrenderer/rendering_method.mobile="gl_compatibility"'}\n`,
    'Main.tscn': generation === 3
      ? '[gd_scene load_steps=2 format=2]\n[ext_resource path="res://Main.gd" type="Script" id=1]\n[node name="Main" type="Node2D"]\nscript = ExtResource(1)\n'
      : '[gd_scene load_steps=2 format=3]\n[ext_resource type="Script" path="res://Main.gd" id="1"]\n[node name="Main" type="Node2D"]\nscript = ExtResource("1")\n',
    'Main.gd': `extends Node2D\nvar label\nfunc _ready():\n\t${generation === 3 ? 'VisualServer' : 'RenderingServer'}.set_default_clear_color(Color(0.04, 0.04, 0.09))\n\tlabel = Label.new()\n\tlabel.text = "ASTRA / GODOT ${generation}\\nRuntime loaded\\nTouch the screen"\n\tlabel.${generation === 3 ? 'rect_position' : 'position'} = Vector2(32, 160)\n\tlabel.${generation === 3 ? 'rect_scale' : 'scale'} = Vector2(2, 2)\n\tadd_child(label)\n\tprint("ASTRA_GODOT_${generation}_READY")\nfunc _input(event):\n\tif event is InputEventScreenTouch and event.pressed:\n\t\tlabel.text = "ASTRA / GODOT ${generation}\\nTouch OK"\n\t\tprint("ASTRA_GODOT_${generation}_TOUCH")\n`
  };
  const project = path.join(output, `godot${generation}`, 'project');
  const pack = path.join(output, `godot${generation}`, 'pack');
  fs.mkdirSync(project, { recursive: true });
  fs.mkdirSync(pack, { recursive: true });
  for (const [name, text] of Object.entries(files)) fs.writeFileSync(path.join(project, name), text);
  fs.writeFileSync(path.join(pack, 'Astra.pck'), createPack(generation, files));
  console.log(`Godot ${generation}: generated separate project and pack fixtures in ${path.dirname(project)}`);
}
