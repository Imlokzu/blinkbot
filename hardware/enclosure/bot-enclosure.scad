// Claude Bot desktop enclosure, phase 1.
//
// One parametric file for the cardboard prototype and the later 3D-printed
// shell. Every opening is declared once in a cutout list; the flat templates,
// the solid model and the fit checks are all generated from that list, so a
// template can never disagree with the model.
//
// The front is almost all screen (about 72 % of the width): mic grille and
// magnetic charging sit in the bottom bezel, keys and the power button on the
// top, the speaker fires sideways. The box is deep rather than wide because the
// power bank lies lengthwise under the Pi.
//
// Parts are the phase-1 set, with dimensions from official drawings where they
// exist (sources next to each group). Everything connects over USB/HDMI;
// nothing is wired to GPIO.
//
// Coordinates are millimetres. X = width, left to right seen from the front.
// Y = depth, front (0) to back. Z = height, floor (0) to top.
//
// Values marked UNVERIFIED are not published by the maker: measure the real
// part before cutting.

/* [Output] */
mode = "assembled"; // [assembled, exploded, net, pieces, print]
material = "cardboard"; // [cardboard, print]
only = ""; // [, front, back, top, bottom, left, right, shelf] pieces mode: one A4-sized piece at the origin

/* [Enclosure] */
cardboard_wall = 3;     // single-wall corrugated board
print_wall = 2.4;
clearance = 4;          // air and cable room around every component
screen_share = 0.73;    // visible screen width / box width, at most
bottom_bezel = 16;      // below the screen module: mic grille and charging
glue_tab = 15;
tuck_flap = 12;         // closes the cardboard back door
line_width = 0.4;       // template line thickness

/* [Raspberry Pi 3 Model B: official drawing RPI-3B-V1_2] */
// Board coordinates: x along the 85 mm edge from the micro-USB end, y from the
// HDMI edge, z up from the board's top surface.
pi_size = [85, 56];
pi_board_t = 1.4;       // UNVERIFIED (not on the drawing; both OpenSCAD libraries use 1.4)
pi_holes = [[3.5, 3.5], [61.5, 3.5], [3.5, 52.5], [61.5, 52.5]];
pi_hole_d = 2.75;
pi_top_h = 16;          // USB stacks, the tallest part
pi_bottom_h = 2;        // UNVERIFIED: microSD and solder joints
pi_usb_y = [29, 47];    // USB stack centres on the far edge
pi_usb = [17.4, 13.3];  // depth along x (UNVERIFIED), width
pi_eth = [10.25, 21.3, 15.8, 13.5]; // centre y, depth (UNVERIFIED), width, height
pi_far_overhang = 2.1;  // USB/Ethernet past the board edge
pi_hdmi = [32, 14.7, 11.5, 6.5, 1.75]; // centre x, width, depth, height, overhang
pi_microusb = [10.6, 7.7, 5.6, 3, 1.4];  // height UNVERIFIED
pi_jack = [53.5, 6, 12, 6, 2.75];        // width/overhang read from the drawing
standoff_h = 6;
standoff_d = 6;

/* [Screen: Waveshare 3.5inch HDMI LCD (E), 640x480] */
// Power and capacitive touch share one USB-C port; HDMI, USB-C and the audio
// jack are all on the top edge.
screen_size = [76.6, 63.6];   // outline, X x Z
screen_t = 12;                // UNVERIFIED: thickness is not published
screen_view = [70.68, 53.36]; // active area
screen_view_offset = [0, 2.1]; // bezels: 2.96 sides, 3.02 top, 7.22 bottom
screen_cable_room = 10;       // above the top edge: use right-angle HDMI and USB-C adapters
screen_cable_depth = 20;

/* [Power bank: Redmi 20000 mAh PB200LZM] */
// Documented to keep 5 V 2.4 A output while charging. Lies lengthwise, ports at the back.
bank_size = [73.6, 154, 27.3];
bank_plug_room = 20;

/* [USB parts] */
hub_size = [57.4, 52.4, 18];      // Raspberry Pi USB 3 Hub (works on the Pi 3's USB 2.0)
sound_size = [23.5, 53, 14.6];    // Waveshare USB TO AUDIO, lying lengthwise
usb_plug_room = 30;               // behind the Pi's USB/Ethernet end
hdmi_plug_room = 14;              // beside the Pi's HDMI port, right-angle adapter
speaker_d = 40;                   // UNVERIFIED: generic 40 mm 4 ohm speaker
speaker_depth = 20;
mic_size = [22.2, 18.3, 7];       // Adafruit Mini USB Microphone (#3367)
mic_grille = [16, 6];
mic_hole = 1.5;
mic_pitch = 3;

/* [Controls] */
power_d = 12;
keys = 3;
key_cap = 12;
key_pitch = 18;
key_depth = 15;
charge_cut = [14, 7];       // magnetic charging connector
charge_lift = 6;            // above the bottom edge of the front panel

/* [Back ports] */
// Panel-mount extensions from the hub, seen from behind, top row first.
port_rows = [["usbc", "usbc", "jack"], ["usba", "usba"]];
usbc_cut = [9.5, 3.8];      // UNVERIFIED: no published cutout
usba_cut = [13.5, 6.5];     // UNVERIFIED: no published cutout
jack_d = 6.5;
port_screw_d = 3.2;         // M3
usbc_screw_pitch = 20;      // Adafruit #4053
usba_screw_pitch = 30;      // Adafruit #908
port_gap = 4;
port_top = 14;              // top row centre below the top edge
port_row_pitch = 16;
port_body = [20, 10];       // connector body behind the panel: depth, height

/* [Ventilation and grilles] */
vent = [2, 25];
vent_pitch = 6;
vents = 8;
speaker_hole = 3;
speaker_pitch = 5;

/* [3D print] */
boss = 8;                   // screw columns in the back corners
screw_pilot_d = 2.5;        // M3 self-tapping
ledge = 4;                  // shelf support ribs
fit = 0.3;

/* [Hidden] */
$fn = 36;
eps = 0.01;

// ---------------------------------------------------------------- layout

printing = material == "print" || mode == "print";
wall = printing ? print_wall : cardboard_wall;
clr = clearance;

key_len = key_cap + (keys - 1) * key_pitch;
key_body = key_cap + 4;

// The Pi lies lengthwise with its USB/Ethernet end to the back, so the plugs
// use the depth, and its HDMI edge faces the right wall.
pi_w = pi_size[1];
pi_bb = [pi_size[1] + pi_hdmi[4], pi_size[0] + pi_far_overhang]; // with overhangs, X x Y

iw_need = max(screen_size[0] + 2 * clr,
              bank_size[0] + 2 * clr,
              clr + pi_bb[0] + hdmi_plug_room + clr,
              clr + hub_size[0] + clr + sound_size[0] + clr);
W = max(iw_need + 2 * wall, screen_view[0] / screen_share);
iw = W - 2 * wall;

front_zone = max(screen_t, mic_size[1], 15);
pi_y = wall + max(front_zone, screen_cable_depth) + clr;
hub_y = pi_y + pi_bb[1] + usb_plug_room;
D = max(hub_y + max(hub_size[1], sound_size[1]) + clr,
        pi_y + bank_size[1] + bank_plug_room) + wall + (printing ? boss : 0);

// The power bank lies on the floor; the Pi, hub and sound card sit on a shelf
// above it, which keeps the bank away from the Pi's heat.
shelf_z = wall + bank_size[2] + clr;
shelf_top = shelf_z + wall;
pi_z = shelf_top + standoff_h;          // underside of the board
pi_top = pi_z + pi_board_t + pi_top_h;
screen_z0 = wall + bottom_bezel;
screen_top = screen_z0 + screen_size[1];
H = max(screen_top + screen_cable_room + wall,
        pi_top + clr + key_depth + wall,
        shelf_top + clr + speaker_d + clr + wall);

scx = W / 2;
screen_x0 = scx - screen_size[0] / 2;
view_c = [scx + screen_view_offset[0], screen_z0 + screen_size[1] / 2 + screen_view_offset[1]];
mic_x = screen_x0 + mic_size[0] / 2 + 2;
mic_z = wall + bottom_bezel / 2;
key_y = wall + screen_cable_depth + clr + key_body / 2;
key0_x = wall + clr + key_cap / 2;
power_x = W - wall - clr - power_d / 2;

pi_x = wall + clr;
pi_cx = pi_x + pi_w / 2;
pi_cy = pi_y + pi_size[0] / 2;
function pi_xy(p) = [pi_x + pi_w - p[1], pi_y + p[0]]; // board -> box
hdmi_y = pi_y + pi_hdmi[0];
speaker_y = hdmi_y + 8 + clr + speaker_d / 2;
speaker_z = shelf_top + clr + speaker_d / 2;
bank_x = (W - bank_size[0]) / 2;
sound_x = wall + clr + hub_size[0] + clr;
shelf_y0 = pi_y - clr;
shelf_y1 = D - wall - (printing ? boss + fit : 0);
// Intake slots stay below the shelf so they never cut the line it is glued on.
side_vent_h = min(vent[1], shelf_z - wall - 16);
side_vent_z = wall + (shelf_z - wall) / 2;

// ---------------------------------------------------------------- cutouts
// Entry: [face, name, cx, cy, kind, a, b]. Centres use each face's natural
// coordinates: front/back (X, Z), top/bottom/shelf (X, Y), left/right (Y, Z).

function vent_off(i) = (i - (vents - 1) / 2) * vent_pitch;
function port_dims(k) = k == "usbc" ? usbc_cut : k == "usba" ? usba_cut : [jack_d, jack_d];
function port_kind(k) = k == "jack" ? "circle" : k;
function screw_pitch(k) = k == "usbc" ? usbc_screw_pitch : usba_screw_pitch;
function port_bw(k) = k == "jack" ? jack_d : screw_pitch(k) + port_screw_d;
function sum_to(v, n) = n <= 0 ? 0 : v[n - 1] + sum_to(v, n - 1);
function row_w(row) = [for (k = row) port_bw(k)];
function row_total(row) = sum_to(row_w(row), len(row)) + port_gap * (len(row) - 1);
function port_u(row, i) = W / 2 - row_total(row) / 2 + sum_to(row_w(row), i) + port_gap * i + row_w(row)[i] / 2;

front_cuts =
  [["front", "screen window", view_c[0], view_c[1], "rect", screen_view[0], screen_view[1]],
   ["front", "microphone grille", mic_x, mic_z, "mic", mic_grille[0], mic_grille[1]],
   ["front", "charge connector", scx, charge_lift + charge_cut[1] / 2, "slot", charge_cut[0], charge_cut[1]]];

top_cuts = concat(
  [for (i = [0 : vents - 1]) ["top", str("vent ", i + 1), pi_cx + vent_off(i), pi_cy, "slot", vent[0], vent[1]]],
  [for (i = [0 : keys - 1]) ["top", str("key ", i + 1), key0_x + i * key_pitch, key_y, "rect", key_cap + 1, key_cap + 1]],
  [["top", "power button", power_x, key_y, "circle", power_d, power_d]]);

side_cuts = concat(
  [for (f = ["left", "right"], i = [0 : vents - 1])
    [f, str("vent ", i + 1), pi_cy + vent_off(i), side_vent_z, "slot", vent[0], side_vent_h]],
  [["right", "speaker grille", speaker_y, speaker_z, "spk", speaker_d - 6, speaker_d - 6]]);

// The Pi's only HDMI output feeds the screen, so the back has no HDMI opening.
port_cuts = [for (r = [0 : len(port_rows) - 1], i = [0 : len(port_rows[r]) - 1]) let(k = port_rows[r][i])
  ["back", str(k, " ", r + 1, ".", i + 1), W - port_u(port_rows[r], i), H - port_top - r * port_row_pitch,
   port_kind(k), port_dims(k)[0], port_dims(k)[1]]];

shelf_cuts = concat(
  [for (h = pi_holes) let(p = pi_xy(h)) ["shelf", "pi hole", p[0], p[1], "circle", pi_hole_d, pi_hole_d]],
  [for (i = [0 : vents - 1]) ["shelf", str("vent ", i + 1), pi_cx + vent_off(i), pi_cy, "slot", vent[0], vent[1]]]);

cuts = concat(front_cuts, top_cuts, side_cuts, port_cuts);
all_cuts = concat(cuts, shelf_cuts);

// ---------------------------------------------------------------- parts
// Entry: [name, position, size, colour] as axis-aligned boxes for the fit
// checks. Colour "zone" marks reserved space (plugs, cables) that is checked
// but not drawn; real parts get a detailed model in part_model().

parts = concat(
  [["screen", [screen_x0, wall, screen_z0], [screen_size[0], screen_t, screen_size[1]], "part"],
   ["screen cables", [screen_x0, wall, screen_top], [screen_size[0], screen_cable_depth, screen_cable_room], "zone"],
   ["microphone", [mic_x - mic_size[0] / 2, wall, mic_z - mic_size[2] / 2], mic_size, "part"],
   ["charge connector", [scx - charge_cut[0] / 2, wall, charge_lift], [charge_cut[0], 15, charge_cut[1]], "part"],
   ["power bank", [bank_x, pi_y, wall], bank_size, "part"],
   ["power bank plugs", [bank_x, pi_y + bank_size[1], wall], [bank_size[0], bank_plug_room, bank_size[2]], "zone"],
   ["shelf", [wall, shelf_y0, shelf_z], [iw, shelf_y1 - shelf_y0, wall], "zone"],
   ["raspberry pi", [pi_x, pi_y, pi_z - pi_bottom_h], [pi_bb[0], pi_bb[1], pi_bottom_h + pi_board_t + pi_top_h], "part"],
   ["pi usb plugs", [pi_x, pi_y + pi_bb[1], pi_z], [pi_w, usb_plug_room, pi_board_t + pi_top_h], "zone"],
   ["hdmi plug", [pi_x + pi_bb[0], hdmi_y - 8, pi_z], [hdmi_plug_room, 16, pi_board_t + 12], "zone"],
   ["speaker", [W - wall - speaker_depth, speaker_y - speaker_d / 2, speaker_z - speaker_d / 2], [speaker_depth, speaker_d, speaker_d], "part"],
   ["usb hub", [wall + clr, hub_y, shelf_top], hub_size, "part"],
   ["sound card", [sound_x, hub_y, shelf_top], sound_size, "part"],
   ["keys", [wall + clr, key_y - key_body / 2, H - wall - key_depth], [key_len, key_body, key_depth], "part"],
   ["power button", [power_x - power_d / 2, key_y - power_d / 2, H - wall - 15], [power_d, power_d, 15], "part"]],
  [for (c = port_cuts) let(b = bbox(c))
    [str("port ", c[1]), [c[2] - b[0] / 2, D - wall - port_body[0] - (printing ? 0 : 0), c[3] - port_body[1] / 2], [b[0], port_body[0], port_body[1]], "port"]],
  printing ? [for (x = [wall, W - wall - boss]) ["screw column", [x, D - wall - boss, wall], [boss, boss, H - 2 * wall], "zone"]] : []);

screw_points = [for (x = [wall + boss / 2, W - wall - boss / 2], z = [wall + 10, H - wall - 10]) [x, z]];

// ---------------------------------------------------------------- checks

function r1(x) = round(x * 10) / 10;
function face_size(f) = (f == "front" || f == "back") ? [W, H] : (f == "top" || f == "bottom") ? [W, D] : [D, H];
function face_lo(f) = f == "shelf" ? [wall, shelf_y0] : [0, 0];
function face_hi(f) = f == "shelf" ? [W - wall, shelf_y1] : face_size(f);
function bbox(c) = (c[4] == "usbc" || c[4] == "usba") ? [screw_pitch(c[4]) + port_screw_d, max(c[6], port_screw_d)] : [c[5], c[6]];
function inside_face(c) = let(b = bbox(c), lo = face_lo(c[0]) + [5, 5], hi = face_hi(c[0]) - [5, 5])
  c[2] - b[0] / 2 >= lo[0] - eps && c[2] + b[0] / 2 <= hi[0] + eps &&
  c[3] - b[1] / 2 >= lo[1] - eps && c[3] + b[1] / 2 <= hi[1] + eps;
function gap(p, q) = let(a = bbox(p), b = bbox(q))
  max(abs(p[2] - q[2]) - (a[0] + b[0]) / 2, abs(p[3] - q[3]) - (a[1] + b[1]) / 2);
function inside_box(p) = let(a = p[1], b = p[1] + p[2])
  a[0] >= wall - eps && a[1] >= wall - eps && a[2] >= wall - eps &&
  b[0] <= W - wall + eps && b[1] <= D - wall + eps && b[2] <= H - wall + eps;
function overlap(p, q) = let(a = p[1], b = p[1] + p[2], c = q[1], d = q[1] + q[2])
  a[0] < d[0] - eps && c[0] < b[0] - eps &&
  a[1] < d[1] - eps && c[1] < b[1] - eps &&
  a[2] < d[2] - eps && c[2] < b[2] - eps;

module checks() {
  for (c = all_cuts)
    assert(inside_face(c), str("Cutout '", c[1], "' on ", c[0], " is closer than 5 mm to the panel edge"));
  for (i = [0 : len(all_cuts) - 2], j = [i + 1 : len(all_cuts) - 1])
    if (all_cuts[i][0] == all_cuts[j][0])
      assert(gap(all_cuts[i], all_cuts[j]) >= 3 - eps,
             str("Cutouts '", all_cuts[i][1], "' and '", all_cuts[j][1], "' on ", all_cuts[i][0], " are closer than 3 mm"));
  for (p = parts)
    assert(inside_box(p), str("Part '", p[0], "' does not fit inside the walls"));
  for (i = [0 : len(parts) - 2], j = [i + 1 : len(parts) - 1])
    assert(!overlap(parts[i], parts[j]), str("Parts '", parts[i][0], "' and '", parts[j][0], "' collide"));
}

module report() {
  echo(str("Outer size W x D x H: ", r1(W), " x ", r1(D), " x ", r1(H), " mm (", material, ", wall ", wall, " mm)"));
  echo(str("Visible screen: ", r1(100 * screen_view[0] / W), " % of the width, ",
           r1(100 * screen_view[1] / H), " % of the height"));
  echo(str("Shelf: underside at Z = ", r1(shelf_z), " mm, ", r1(iw), " x ", r1(shelf_y1 - shelf_y0), " mm"));
  for (c = all_cuts) let(b = bbox(c))
    echo(str(c[0], " | ", c[1], " | centre ", r1(c[2]), ", ", r1(c[3]), " | ", r1(b[0]), " x ", r1(b[1])));
}

// ---------------------------------------------------------------- 2D shapes

module slot(a, b) {
  d = min(a, b);
  hull() {
    translate(a >= b ? [-(a - d) / 2, 0] : [0, -(b - d) / 2]) circle(d = d);
    translate(a >= b ? [(a - d) / 2, 0] : [0, (b - d) / 2]) circle(d = d);
  }
}

module grille_rect(w, h, d, p) {
  nx = floor((w - d) / p) + 1;
  ny = floor((h - d) / p) + 1;
  for (i = [0 : nx - 1], j = [0 : ny - 1])
    translate([(i - (nx - 1) / 2) * p, (j - (ny - 1) / 2) * p]) circle(d = d, $fn = 12);
}

module grille_circle(span, d, p) {
  n = floor((span - d) / p / 2);
  for (i = [-n : n], j = [-n : n])
    if (norm([i * p, j * p]) <= (span - d) / 2)
      translate([i * p, j * p]) circle(d = d, $fn = 12);
}

module cut2d(c) {
  k = c[4]; a = c[5]; b = c[6];
  if (k == "rect") square([a, b], center = true);
  else if (k == "circle") circle(d = a);
  else if (k == "slot") slot(a, b);
  else if (k == "mic") grille_rect(a, b, mic_hole, mic_pitch);
  else if (k == "spk") grille_circle(a, speaker_hole, speaker_pitch);
  else if (k == "usbc" || k == "usba") {
    if (k == "usbc") slot(a, b); else square([a, b], center = true);
    for (s = [-1, 1]) translate([s * screw_pitch(k) / 2, 0]) circle(d = port_screw_d);
  }
}

// ---------------------------------------------------------------- 3D shell

module cut3d(c) {
  f = c[0]; t = wall + 2;
  if (f == "front") translate([c[2], wall + 1, c[3]]) rotate([90, 0, 0]) linear_extrude(t) cut2d(c);
  else if (f == "back") translate([c[2], D + 1, c[3]]) rotate([90, 0, 0]) linear_extrude(t) cut2d(c);
  else if (f == "top") translate([c[2], c[3], H - wall - 1]) linear_extrude(t) cut2d(c);
  else if (f == "bottom") translate([c[2], c[3], -1]) linear_extrude(t) cut2d(c);
  else if (f == "left") translate([-1, c[2], c[3]]) rotate([90, 0, 90]) linear_extrude(t) cut2d(c);
  else if (f == "right") translate([W - wall - 1, c[2], c[3]]) rotate([90, 0, 90]) linear_extrude(t) cut2d(c);
  else if (f == "shelf") translate([c[2], c[3], shelf_z - 1]) linear_extrude(wall + standoff_h + 2) cut2d(c);
}

module shell_solid() {
  difference() {
    cube([W, D, H]);
    translate([wall, wall, wall]) cube([iw, D - 2 * wall, H - 2 * wall]);
    for (c = cuts) cut3d(c);
  }
}

// Printed body: open back, shelf ribs, and screw columns for the back panel.
module body() {
  difference() {
    union() {
      shell_solid();
      if (printing) {
        for (x = [wall, W - wall - boss]) translate([x, D - wall - boss, wall]) cube([boss, boss, H - 2 * wall]);
        // 45-degree undersides print without support when the body stands on its front.
        for (s = [[wall, 1], [W - wall, -1]])
          translate([0, shelf_y1, 0]) rotate([90, 0, 0]) linear_extrude(shelf_y1 - shelf_y0)
            polygon([[s[0], shelf_z], [s[0] + s[1] * ledge, shelf_z], [s[0], shelf_z - ledge]]);
      }
    }
    if (printing) {
      translate([-1, D - wall, -1]) cube([W + 2, wall + 2, H + 2]);
      for (p = screw_points) translate([p[0], D - wall - boss - 1, p[1]]) rotate([-90, 0, 0]) cylinder(d = screw_pilot_d, h = boss + 2);
    }
  }
}

module back_panel() {
  difference() {
    translate([0, D - wall, 0]) cube([W, wall, H]);
    for (c = port_cuts) cut3d(c);
    if (printing) for (p = screw_points) translate([p[0], D - wall - 1, p[1]]) rotate([-90, 0, 0]) cylinder(d = port_screw_d, h = wall + 2);
  }
}

module shelf_plate() {
  inset = printing ? fit : 0;
  difference() {
    union() {
      translate([wall + inset, shelf_y0, shelf_z]) cube([iw - 2 * inset, shelf_y1 - shelf_y0, wall]);
      if (printing) for (h = pi_holes) let(p = pi_xy(h)) translate([p[0], p[1], shelf_top]) cylinder(d = standoff_d, h = standoff_h);
    }
    for (c = shelf_cuts) cut3d(c);
  }
}

// ---------------------------------------------------------------- part models

// Raspberry Pi 3 Model B in board coordinates, connector positions from the
// official drawing.
module pi_model() {
  color("forestgreen") difference() {
    linear_extrude(pi_board_t) offset(r = 3) offset(delta = -3) square(pi_size);
    for (h = pi_holes) translate([h[0], h[1], -1]) cylinder(d = pi_hole_d, h = pi_board_t + 2);
  }
  translate([0, 0, pi_board_t]) {
    color("silver") {
      for (y = pi_usb_y) translate([pi_size[0] + pi_far_overhang - pi_usb[0], y - pi_usb[1] / 2, 0]) cube([pi_usb[0], pi_usb[1], pi_top_h]);
      translate([pi_size[0] + pi_far_overhang - pi_eth[1], pi_eth[0] - pi_eth[2] / 2, 0]) cube([pi_eth[1], pi_eth[2], pi_eth[3]]);
      translate([pi_hdmi[0] - pi_hdmi[1] / 2, -pi_hdmi[4], 0]) cube([pi_hdmi[1], pi_hdmi[2], pi_hdmi[3]]);
      translate([pi_microusb[0] - pi_microusb[1] / 2, -pi_microusb[4], 0]) cube([pi_microusb[1], pi_microusb[2], pi_microusb[3]]);
    }
    color("black") {
      translate([pi_jack[0] - pi_jack[1] / 2, -pi_jack[4], 0]) cube([pi_jack[1], pi_jack[2], pi_jack[3]]);
      translate([32.5 - 25.5, 52.5 - 2.5, 0]) cube([51, 5, 8.5]); // GPIO header, left empty
    }
    color("dimgray") translate([30, 22, 0]) cube([14, 14, 1.2]);   // SoC
  }
}

module screen_model() {
  color([0.08, 0.08, 0.1]) cube([screen_size[0], 2, screen_size[1]]);
  color([0.2, 0.45, 0.75]) translate([(screen_size[0] - screen_view[0]) / 2 + screen_view_offset[0], -0.05,
                                      (screen_size[1] - screen_view[1]) / 2 + screen_view_offset[1]])
    cube([screen_view[0], 0.1, screen_view[1]]);
  color("royalblue") translate([0, 3.5, 0]) cube([screen_size[0], 1.6, screen_size[1]]);
  // HDMI, USB-C (power + touch) and audio jack on the top edge.
  color("silver") for (c = [[20, 15, 6], [42, 9, 3.5], [58, 6, 6]])
    translate([c[0] - c[1] / 2, 5.1, screen_size[1] - 8]) cube([c[1], c[2], 8]);
}

module rounded_box(s, r) {
  hull() for (x = [r, s[0] - r], y = [r, s[1] - r]) translate([x, y, 0]) cylinder(r = r, h = s[2]);
}

module part_model(p) {
  n = p[0]; o = p[1]; s = p[2];
  if (n == "raspberry pi") translate([pi_x + pi_w, pi_y, pi_z]) rotate([0, 0, 90]) pi_model();
  else if (n == "screen") translate(o) screen_model();
  else if (n == "power bank") translate(o) {
    color("gainsboro") rounded_box(s, 6);
    color("black") for (x = [22, 40]) translate([x, s[1] - 0.5, 10]) cube([12, 1, 5]);
  }
  else if (n == "usb hub") translate(o) {
    color("white") rounded_box(s, 3);
    color("black") for (i = [0 : 3]) translate([4 + i * 13.5, s[1] - 0.5, 6]) cube([12, 1, 5]);
  }
  else if (n == "sound card") translate(o) {
    color("black") cube([s[0], s[1] - 12, s[2]]);
    color("silver") translate([(s[0] - 12) / 2, s[1] - 12, (s[2] - 4.5) / 2]) cube([12, 12, 4.5]);
  }
  else if (n == "microphone") translate(o) {
    color("black") cube([s[0], s[1] - 8, s[2]]);
    color("silver") translate([(s[0] - 12) / 2, s[1] - 8, 1]) cube([12, 8, 4.5]);
  }
  else if (n == "speaker") translate(o + [s[0], s[1] / 2, s[2] / 2]) rotate([0, -90, 0]) {
    color("dimgray") cylinder(d1 = speaker_d, d2 = speaker_d * 0.6, h = speaker_depth * 0.5);
    color("gray") cylinder(d = speaker_d * 0.5, h = speaker_depth);
  }
  else if (n == "keys") translate(o) for (i = [0 : keys - 1]) translate([i * key_pitch, 0, 0]) {
    color("black") translate([0, (key_body - key_cap) / 2, 0]) cube([key_cap, key_cap, key_depth - 4]);
    color("lightgray") translate([0.5, (key_body - key_cap) / 2 + 0.5, key_depth - 4]) cube([key_cap - 1, key_cap - 1, 4]);
  }
  else if (n == "power button") translate(o + [s[0] / 2, s[1] / 2, 0]) color("firebrick") cylinder(d = power_d, h = s[2]);
  else if (n == "charge connector") translate(o + [s[0] / 2, 0, s[2] / 2]) rotate([-90, 0, 0]) color("gold") cylinder(d = s[2], h = s[1]);
  else if (p[3] == "port") translate(o) {
    color("silver") translate([s[0] / 2 - 7, 0, 1]) cube([14, s[1], s[2] - 2]);
    color("black") translate([0, s[1] - 2, 0]) cube([s[0], 2, s[2]]);
  }
}

module parts_view() {
  for (p = parts) if (p[3] != "zone") part_model(p);
}

module assembled() {
  color("burlywood", 0.35) if (printing) { body(); back_panel(); } else shell_solid();
  color("tan") shelf_plate();
  parts_view();
}

module exploded() {
  e = 35;
  slabs = [[[0, 0, 0], [W, wall, H], [0, -e, 0]],
           [[0, D - wall, 0], [W, wall, H], [0, e, 0]],
           [[0, 0, 0], [wall, D, H], [-e, 0, 0]],
           [[W - wall, 0, 0], [wall, D, H], [e, 0, 0]],
           [[0, 0, 0], [W, D, wall], [0, 0, -e]],
           [[0, 0, H - wall], [W, D, wall], [0, 0, e]]];
  color("burlywood") for (s = slabs)
    translate(s[2]) intersection() { shell_solid(); translate(s[0]) cube(s[1]); }
  color("tan") shelf_plate();
  parts_view();
}

module print_layout() {
  translate([0, H, 0]) rotate([90, 0, 0]) body();             // stands on its front: no bridges
  translate([W + 20, 0, 0]) rotate([-90, 0, 0]) translate([0, -D, 0]) back_panel();
  translate([W + 20, H + 20, 0]) translate([-wall, -shelf_y0, -shelf_z]) shelf_plate();
}

// ---------------------------------------------------------------- templates
// Line drawings to print at 100 % and glue on cardboard: solid lines are cuts,
// dashed lines are folds, dotted guides mark where the shelf is glued.

module outline() difference() { offset(delta = line_width) children(); children(); }

module dashes(p1, p2, dash = 3, step = 6) {
  L = norm(p2 - p1);
  dir = (p2 - p1) / L;
  for (i = [0 : floor(L / step) - 1])
    translate(p1 + dir * (i * step + (step - dash) / 2)) rotate(atan2(dir[1], dir[0]))
      translate([0, -line_width / 2]) square([dash, line_width]);
}

module label(s, at) translate(at) text(s, size = 5);

module edge_tab(a, b, v, s, t = glue_tab) polygon([[a, v], [b, v], [b - t, v + s * t], [a + t, v + s * t]]);
module side_tab(u, a, b, s, t = glue_tab) polygon([[u, a], [u, b], [u + s * t, b - t], [u + s * t, a + t]]);

sd = shelf_y1 - shelf_y0;
function net_xy(f, p) =
  f == "top" ? [p[0], H + p[1]] :
  f == "back" ? [p[0], 2 * H + D - p[1]] :
  f == "bottom" ? [p[0], -p[1]] :
  f == "left" ? [-p[0], p[1]] :
  f == "right" ? [W + p[0], p[1]] : p;
// Outside view of a separate piece: what you see when the box is assembled.
function piece_xy(f, p) =
  (f == "back" || f == "bottom") ? [W - p[0], p[1]] :
  f == "left" ? [D - p[0], p[1]] :
  f == "shelf" ? [p[0] - wall, p[1] - shelf_y0] : p;

module shelf_shape() {
  difference() {
    union() {
      square([iw, sd]);
      side_tab(0, 0, sd, -1);
      side_tab(iw, 0, sd, 1);
    }
    for (c = shelf_cuts) translate(piece_xy("shelf", [c[2], c[3]])) cut2d(c);
  }
}

module shelf_marks() {
  dashes([0, 0], [0, sd]);
  dashes([iw, 0], [iw, sd]);
  label("SHELF", [iw / 2 - 12, sd - 12]);
}

shelf_net_at = [W + D + glue_tab + 25, 0];

module net2d() {
  outline() {
    difference() {
      union() {
        square([W, H]);
        translate([0, H]) square([W, D]);
        translate([0, H + D]) square([W, H]);
        translate([0, -D]) square([W, D]);
        translate([-D, 0]) square([D, H]);
        translate([W, 0]) square([D, H]);
        edge_tab(-D, 0, H, 1); edge_tab(-D, 0, 0, -1);
        edge_tab(W, W + D, H, 1); edge_tab(W, W + D, 0, -1);
        edge_tab(0, W, 2 * H + D, 1, tuck_flap);
      }
      for (c = cuts) translate(net_xy(c[0], [c[2], c[3]])) cut2d(c);
    }
    translate(shelf_net_at) shelf_shape();
  }
  for (s = [[[0, H], [W, H]], [[0, H + D], [W, H + D]], [[0, 0], [W, 0]], [[0, 0], [0, H]], [[W, 0], [W, H]],
            [[-D, H], [0, H]], [[-D, 0], [0, 0]], [[W, H], [W + D, H]], [[W, 0], [W + D, 0]],
            [[0, 2 * H + D], [W, 2 * H + D]]])
    dashes(s[0], s[1]);
  dashes([-shelf_y0, shelf_z], [-shelf_y1, shelf_z], 1, 3);
  dashes([W + shelf_y0, shelf_z], [W + shelf_y1, shelf_z], 1, 3);
  label("FRONT", [4, 4]);
  label("TOP", [4, H + 4]);
  label("BACK (door)", [4, H + D + 4]);
  label("BOTTOM", [4, -D + 4]);
  label("LEFT", [-D + 4, 4]);
  label("RIGHT", [W + 4, 4]);
  translate(shelf_net_at) shelf_marks();
}

module pieces2d() {
  g = 12;
  row2 = H + g;
  row3 = H + D + 2 * g;
  pieces = [["front", [0, 0]], ["back", [W + g, 0]], ["top", [0, row2]], ["bottom", [W + g, row2]],
            ["left", [0, row3]], ["right", [D + g, row3]]];
  for (p = pieces) if (only == "" || only == p[0]) translate(only == "" ? p[1] : [0, 0]) {
    outline() difference() {
      square(face_size(p[0]));
      for (c = cuts) if (c[0] == p[0]) translate(piece_xy(p[0], [c[2], c[3]])) cut2d(c);
    }
    if (p[0] == "left") dashes([D - shelf_y0, shelf_z], [D - shelf_y1, shelf_z], 1, 3);
    if (p[0] == "right") dashes([shelf_y0, shelf_z], [shelf_y1, shelf_z], 1, 3);
    label(str(p[0] == "back" ? "BACK (door)" : p[0] == "front" ? "FRONT" : p[0] == "top" ? "TOP" :
              p[0] == "bottom" ? "BOTTOM" : p[0] == "left" ? "LEFT" : "RIGHT", " - outside"), [4, 4]);
  }
  if (only == "" || only == "shelf")
    translate(only == "" ? [2 * D + 2 * g + glue_tab, row3] : [glue_tab, 0]) { outline() shelf_shape(); shelf_marks(); }
}

// ---------------------------------------------------------------- main

checks();
report();
if (mode == "assembled") assembled();
else if (mode == "exploded") exploded();
else if (mode == "net") net2d();
else if (mode == "pieces") pieces2d();
else if (mode == "print") print_layout();
