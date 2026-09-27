// Claude Bot desktop enclosure, phase 1.
//
// One parametric file for the cardboard prototype and the later 3D-printed
// shell. Every opening is declared once in a cutout list; the flat templates,
// the solid model and the fit checks are all generated from that list, so a
// template can never disagree with the model.
//
// Parts are the phase-1 set: Raspberry Pi 3 Model B, a 3.5" HDMI screen, a USB
// microphone, a USB sound card with speaker, a powered USB hub and a
// pass-through power bank. Everything connects over USB/HDMI; nothing is wired
// to GPIO.
//
// Coordinates are millimetres. X = width, left to right seen from the front.
// Y = depth, front (0) to back. Z = height, floor (0) to top.
//
// Every component size below is a default taken from shop listings. Measure
// the real parts and edit these values before cutting anything.

/* [Output] */
mode = "assembled"; // [assembled, exploded, net, pieces, print]
material = "cardboard"; // [cardboard, print]
only = ""; // [, front, back, top, bottom, left, right, shelf] pieces mode: one A4-sized piece at the origin

/* [Enclosure] */
cardboard_wall = 3;     // single-wall corrugated board
print_wall = 2.4;
clearance = 5;          // air and cable room around every component
face_margin = 8;        // free border around the front-panel elements
symmetric = true;       // equal side columns keep the screen ("face") centred
glue_tab = 15;
tuck_flap = 12;         // closes the cardboard back door
line_width = 0.4;       // template line thickness

/* [Raspberry Pi 3 Model B] */
pi_size = [85, 56];
pi_board_t = 1.4;
pi_top_h = 17;          // USB/Ethernet stack above the board
pi_bottom_h = 2;        // microSD and solder joints below the board
pi_holes = [[3.5, 3.5], [61.5, 3.5], [3.5, 52.5], [61.5, 52.5]]; // M2.5, board-local
pi_hole_d = 2.7;
standoff_h = 6;
standoff_d = 6;

/* [Screen] */
screen_size = [86, 57];     // module, X x Z
screen_t = 10;
screen_view = [72, 54];     // visible area
screen_view_offset = [0, 0];
screen_raise = 4;           // the face sits slightly above centre
hdmi_gap = 20;              // behind the screen for a flexible HDMI cable

/* [Power bank] */
bank_size = [140, 68, 27];  // 20 000 mAh, two outputs, pass-through

/* [USB parts] */
hub_size = [60, 25, 12];
sound_size = [35, 25, 10];
speaker_d = 40;
speaker_depth = 20;
mic_size = [20, 18, 45];    // standing upright
mic_grille = [12, 32];

/* [Controls] */
power_d = 12;
keys = 3;
key_cap = 12;
key_pitch = 18;
key_depth = 15;
keys_on = "top"; // [top, front]
charge_cut = [14, 7];       // magnetic charging connector
charge_lift = 6;            // above the bottom edge of the front panel

/* [Back ports] */
ports = ["usbc", "usbc", "usba", "usba", "jack"]; // left to right, seen from behind
usbc_cut = [9.5, 3.8];
usba_cut = [13.5, 6.5];
jack_d = 6.5;
port_screw_d = 3.2;
port_screw_pitch = 25;
port_pitch = 34;
port_top = 14;              // row centre below the top edge
port_body = [20, 10];       // panel-mount connector body: depth, height
cable_room = 25;            // behind the Pi for connector bodies and cable bends

/* [Ventilation and grilles] */
vent = [2, 25];
vent_pitch = 6;
vents = 8;
mic_hole = 2;
mic_pitch = 4;
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

col_l = mic_size[0];
col_r = max(speaker_d, power_d, keys_on == "front" ? key_body : 0);
cl = symmetric ? max(col_l, col_r) : col_l;
cr = symmetric ? max(col_l, col_r) : col_r;

iw = max(clr + cl + clr + screen_size[0] + clr + cr + clr,
         clr + pi_size[0] + clr + max(hub_size[0], sound_size[0]) + clr,
         clr + bank_size[0] + clr);
W = iw + 2 * wall;

front_zone = max(screen_t, speaker_depth, mic_size[1], keys_on == "front" ? key_depth : 0);
pi_y = wall + max(screen_t + hdmi_gap, front_zone + clr);
stack_d = max(pi_size[1], hub_size[1] + clr + sound_size[1], bank_size[1]);
D = pi_y + stack_d + cable_room + wall;

// The power bank lies on the floor; the Pi, hub and sound card sit on a shelf
// above it, which keeps the bank away from the Pi's heat.
shelf_z = wall + bank_size[2] + clr;
shelf_top = shelf_z + wall;
pi_z = shelf_top + standoff_h;
pi_top = pi_z + pi_board_t + pi_top_h;
right_col_h = power_d + clr + (keys_on == "front" ? key_len + clr : 0) + speaker_d;
H = max(pi_top + clr + (keys_on == "top" ? key_depth : clr) + wall,
        2 * wall + 2 * face_margin + max(screen_size[1] + 2 * screen_raise, right_col_h, mic_size[2]));

scx = symmetric ? W / 2 : wall + clr + cl + clr + screen_size[0] / 2;
scz = H / 2 + screen_raise;
scr_l = scx - screen_size[0] / 2;
scr_r = scx + screen_size[0] / 2;
mic_x = (wall + scr_l) / 2;
mic_z = scz;
col_x = (scr_r + W - wall) / 2;
power_z = H - wall - face_margin - power_d / 2;
speaker_z = wall + face_margin + speaker_d / 2;
keys_front_z = (power_z - power_d / 2 + speaker_z + speaker_d / 2) / 2;
key_y = wall + front_zone + clr + key_body / 2;
key0_x = wall + clr + key_cap / 2;

pi_x = wall + clr;
pi_cx = pi_x + pi_size[0] / 2;
pi_cy = pi_y + pi_size[1] / 2;
hub_x = pi_x + pi_size[0] + clr;
sound_y = pi_y + hub_size[1] + clr;
bank_x = (W - bank_size[0]) / 2;
shelf_y0 = pi_y - clr;
shelf_y1 = D - wall - (printing ? boss + fit : 0);
port_z = H - port_top;
// Intake slots stay below the shelf so they never cut the line it is glued on.
side_vent_h = min(vent[1], shelf_z - wall - 2 * face_margin);
side_vent_z = wall + (shelf_z - wall) / 2;

// ---------------------------------------------------------------- cutouts
// Entry: [face, name, cx, cy, kind, a, b]. Centres use each face's natural
// coordinates: front/back (X, Z), top/bottom/shelf (X, Y), left/right (Y, Z).

function port_dims(k) = k == "usbc" ? usbc_cut : k == "usba" ? usba_cut : [jack_d, jack_d];
function port_kind(k) = k == "jack" ? "circle" : k;
function port_u(i) = W / 2 + (i - (len(ports) - 1) / 2) * port_pitch;
function vent_off(i) = (i - (vents - 1) / 2) * vent_pitch;

front_cuts = concat(
  [["front", "screen window", scx + screen_view_offset[0], scz + screen_view_offset[1], "rect", screen_view[0], screen_view[1]],
   ["front", "microphone grille", mic_x, mic_z, "mic", mic_grille[0], mic_grille[1]],
   ["front", "power switch", col_x, power_z, "circle", power_d, power_d],
   ["front", "speaker grille", col_x, speaker_z, "spk", speaker_d - 6, speaker_d - 6],
   ["front", "charge connector", scx, charge_lift + charge_cut[1] / 2, "slot", charge_cut[0], charge_cut[1]]],
  keys_on == "front"
    ? [for (i = [0 : keys - 1]) ["front", str("key ", i + 1), col_x, keys_front_z + ((keys - 1) / 2 - i) * key_pitch, "rect", key_cap + 1, key_cap + 1]]
    : []);

top_cuts = concat(
  [for (i = [0 : vents - 1]) ["top", str("vent ", i + 1), pi_cx + vent_off(i), pi_cy, "slot", vent[0], vent[1]]],
  keys_on == "top"
    ? [for (i = [0 : keys - 1]) ["top", str("key ", i + 1), key0_x + i * key_pitch, key_y, "rect", key_cap + 1, key_cap + 1]]
    : []);

side_cuts = [for (f = ["left", "right"], i = [0 : vents - 1])
  [f, str("vent ", i + 1), pi_cy + vent_off(i), side_vent_z, "slot", vent[0], side_vent_h]];

// The Pi's only HDMI output feeds the screen, so the back has no HDMI opening.
port_cuts = [for (i = [0 : len(ports) - 1])
  ["back", str(ports[i], " ", i + 1), W - port_u(i), port_z, port_kind(ports[i]), port_dims(ports[i])[0], port_dims(ports[i])[1]]];

shelf_cuts = concat(
  [for (h = pi_holes) ["shelf", "pi hole", pi_x + h[0], pi_y + h[1], "circle", pi_hole_d, pi_hole_d]],
  [for (i = [0 : vents - 1]) ["shelf", str("vent ", i + 1), pi_cx + vent_off(i), pi_cy, "slot", vent[0], vent[1]]]);

cuts = concat(front_cuts, top_cuts, side_cuts, port_cuts);
all_cuts = concat(cuts, shelf_cuts);

// ---------------------------------------------------------------- parts
// Entry: [name, position, size, colour], all axis-aligned boxes.

parts = concat(
  [["screen", [scx - screen_size[0] / 2, wall, scz - screen_size[1] / 2], [screen_size[0], screen_t, screen_size[1]], "black"],
   ["microphone", [mic_x - mic_size[0] / 2, wall, mic_z - mic_size[2] / 2], mic_size, "dimgray"],
   ["speaker", [col_x - speaker_d / 2, wall, speaker_z - speaker_d / 2], [speaker_d, speaker_depth, speaker_d], "gray"],
   ["power switch", [col_x - power_d / 2, wall, power_z - power_d / 2], [power_d, 15, power_d], "red"],
   ["charge connector", [scx - charge_cut[0] / 2, wall, charge_lift], [charge_cut[0], 15, charge_cut[1]], "gold"],
   ["power bank", [bank_x, pi_y, wall], bank_size, "steelblue"],
   ["shelf", [wall, shelf_y0, shelf_z], [iw, shelf_y1 - shelf_y0, wall], "tan"],
   ["raspberry pi", [pi_x, pi_y, pi_z - pi_bottom_h], [pi_size[0], pi_size[1], pi_bottom_h + pi_board_t + pi_top_h], "green"],
   ["usb hub", [hub_x, pi_y, shelf_top], hub_size, "white"],
   ["sound card", [hub_x, sound_y, shelf_top], sound_size, "purple"],
   keys_on == "top"
     ? ["keys", [wall + clr, key_y - key_body / 2, H - wall - key_depth], [key_len, key_body, key_depth], "orange"]
     : ["keys", [col_x - key_body / 2, wall, keys_front_z - key_len / 2], [key_body, key_depth, key_len], "orange"]],
  [for (c = port_cuts) [str("port ", c[1]), [c[2] - bbox(c)[0] / 2, D - wall - port_body[0], port_z - port_body[1] / 2],
                        [bbox(c)[0], port_body[0], port_body[1]], "silver"]],
  printing ? [for (x = [wall, W - wall - boss]) ["screw column", [x, D - wall - boss, wall], [boss, boss, H - 2 * wall], "burlywood"]] : []);

screw_points = [for (x = [wall + boss / 2, W - wall - boss / 2], z = [wall + 10, H - wall - 10]) [x, z]];

// ---------------------------------------------------------------- checks

function r1(x) = round(x * 10) / 10;
function face_size(f) = (f == "front" || f == "back") ? [W, H] : (f == "top" || f == "bottom") ? [W, D] : [D, H];
function face_lo(f) = f == "shelf" ? [wall, shelf_y0] : [0, 0];
function face_hi(f) = f == "shelf" ? [W - wall, shelf_y1] : face_size(f);
function bbox(c) = (c[4] == "usbc" || c[4] == "usba") ? [port_screw_pitch + port_screw_d, max(c[6], port_screw_d)] : [c[5], c[6]];
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
    for (s = [-1, 1]) translate([s * port_screw_pitch / 2, 0]) circle(d = port_screw_d);
  }
}

// ---------------------------------------------------------------- 3D model

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
    for (p = screw_points) translate([p[0], D - wall - 1, p[1]]) rotate([-90, 0, 0]) cylinder(d = port_screw_d, h = wall + 2);
  }
}

module shelf_plate() {
  inset = printing ? fit : 0;
  difference() {
    union() {
      translate([wall + inset, shelf_y0, shelf_z]) cube([iw - 2 * inset, shelf_y1 - shelf_y0, wall]);
      if (printing) for (h = pi_holes) translate([pi_x + h[0], pi_y + h[1], shelf_top]) cylinder(d = standoff_d, h = standoff_h);
    }
    for (c = shelf_cuts) cut3d(c);
  }
}

module parts_view() {
  for (p = parts) if (p[0] != "shelf" && p[0] != "screw column")
    color(p[3]) translate(p[1]) cube(p[2]);
}

module assembled() {
  color("burlywood", 0.35) if (printing) { body(); back_panel(); } else shell_solid();
  color("tan") shelf_plate();
  parts_view();
}

module exploded() {
  e = 30;
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
  label("SHELF", [iw - 40, 4]);
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
