"""Offscreen render of a SOMA model with VTK, for checking the model outside the app.

Run with SOMA's Python (it has cadquery and vtk):
  $SOMA_PY -I tools/render.py <project> <out.png> --view=-1,-1.7,0.8 [--hide a,b] [--only a,b] [--zoom z]
--view is the direction from the model to the camera; --hide / --only match part names or groups.
"""
import sys, os, argparse
ap = argparse.ArgumentParser()
ap.add_argument("project"); ap.add_argument("out"); ap.add_argument("--view", default="-1,-1.6,0.9")
ap.add_argument("--hide", default=""); ap.add_argument("--only", default=""); ap.add_argument("--zoom", type=float, default=1.0)
ap.add_argument("--size", default="1400x1050"); ap.add_argument("--focus", default="")
a = ap.parse_args()
os.chdir(a.project); sys.path.insert(0, a.project)
from soma import build
import vtk
parts = build.run_model(os.path.abspath(a.project))
hide = [h for h in a.hide.split(",") if h]; only = [h for h in a.only.split(",") if h]
def match(p, keys):
    path = "/".join(p["group"] + (p["name"],))
    return any(k.lower() in path.lower() for k in keys)
ren = vtk.vtkRenderer(); ren.SetBackground(0.96, 0.96, 0.97); ren.SetBackground2(0.82, 0.84, 0.88); ren.GradientBackgroundOn()
bounds = []
for p in parts:
    if hide and match(p, hide): continue
    if only and not match(p, only): continue
    pd = p["shape"].toVtkPolyData(0.05, 0.2)
    n = vtk.vtkPolyDataNormals(); n.SetInputData(pd); n.SetFeatureAngle(35); n.Update()
    m = vtk.vtkPolyDataMapper(); m.SetInputConnection(n.GetOutputPort()); m.ScalarVisibilityOff()
    act = vtk.vtkActor(); act.SetMapper(m)
    c = p["color"]; act.GetProperty().SetColor(*c[:3]); act.GetProperty().SetOpacity(c[3] if len(c) > 3 else 1)
    pr = act.GetProperty(); pr.SetAmbient(0.28); pr.SetDiffuse(0.85); pr.SetSpecular(0.18); pr.SetSpecularPower(25)
    if p["name"] in ("LED glow",): act.GetProperty().SetAmbient(0.9)
    ren.AddActor(act)
w, h = map(int, a.size.split("x"))
rw = vtk.vtkRenderWindow(); rw.SetOffScreenRendering(1); rw.AddRenderer(ren); rw.SetSize(w, h); rw.SetMultiSamples(8)
ren.ResetCamera()
cam = ren.GetActiveCamera()
d = [float(x) for x in a.view.split(",")]
fp = cam.GetFocalPoint()
if a.focus:
    fp = [float(x) for x in a.focus.split(",")]
dist = 600
cam.SetFocalPoint(*fp); cam.SetPosition(fp[0] + d[0] * dist, fp[1] + d[1] * dist, fp[2] + d[2] * dist); cam.SetViewUp(0, 0, 1)
ren.ResetCamera() if not a.focus else None
if a.focus: cam.SetFocalPoint(*fp)
cam.Zoom(a.zoom)
ren.ResetCameraClippingRange()
light = vtk.vtkLight(); light.SetLightTypeToCameraLight(); light.SetPosition(0.4, 0.6, 1); light.SetIntensity(0.45); ren.AddLight(light)
rw.Render()
wi = vtk.vtkWindowToImageFilter(); wi.SetInput(rw); wi.Update()
wr = vtk.vtkPNGWriter(); wr.SetFileName(a.out); wr.SetInputConnection(wi.GetOutputPort()); wr.Write()
print("wrote", a.out)
