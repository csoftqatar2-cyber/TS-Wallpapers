"""
Cross-check every `findViewById(R.id.x)` against the tag that declares x in the layouts.

WHY THIS EXISTS
    findViewById infers its return type from the variable it is assigned to, so

        TextView edit = findViewById(R.id.buttonWallpaperEdit);   // <ImageView> in the XML

    compiles perfectly and throws ClassCastException the first time the view is inflated —
    i.e. on the head unit, in front of a customer, never on the build server. This project has
    shipped that exact bug three times: CheckBox->Switch, LinearLayout->Button, and
    TextView->ImageView. The compiler cannot catch it. This can.

USAGE
    python tools/check_view_types.py      # exit 1 if any field type disagrees with its XML tag

If it reports a mismatch, fix the Java field type (or the XML tag) — do not add the pair to OK
below unless the cast is genuinely safe, i.e. the Java type is a real superclass of the widget.
"""
import io, sys, re, glob, os
import xml.etree.ElementTree as ET
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
A = "{http://schemas.android.com/apk/res/android}"

# id -> set of XML tags that declare it
decl = {}
for p in glob.glob("source/app/src/main/res/layout*/*.xml"):
    try: t = ET.parse(p)
    except Exception: continue
    for e in t.iter():
        i = e.get(A + "id")
        if i and i.startswith("@+id/"):
            decl.setdefault(i[5:], set()).add(e.tag.split("}")[-1])

# What a Java field type will accept for a given XML tag.
OK = {
 "TextView":   {"TextView","AppCompatTextView","View","Object"},
 "ImageView":  {"ImageView","AppCompatImageView","View","Object"},
 "CheckBox":   {"CheckBox","AppCompatCheckBox","CompoundButton","Button","TextView","View"},
 "RadioButton":{"RadioButton","CompoundButton","Button","TextView","View"},
 "Button":     {"Button","AppCompatButton","TextView","View"},
 "Spinner":    {"Spinner","AdapterView","View"},
 "SeekBar":    {"SeekBar","AbsSeekBar","ProgressBar","View"},
 "EditText":   {"EditText","AppCompatEditText","TextView","View"},
 "LinearLayout":{"LinearLayout","ViewGroup","View"},
 "FrameLayout":{"FrameLayout","ViewGroup","View"},
 "GridView":   {"GridView","AbsListView","AdapterView","ViewGroup","View"},
 "ListView":   {"ListView","AbsListView","AdapterView","ViewGroup","View"},
 "ProgressBar":{"ProgressBar","View"},
 "RadioGroup": {"RadioGroup","LinearLayout","ViewGroup","View"},
 "com.google.android.material.switchmaterial.SwitchMaterial":
               {"SwitchMaterial","SwitchCompat","CompoundButton","Button","TextView","View"},
 "com.google.android.material.card.MaterialCardView":
               {"MaterialCardView","CardView","FrameLayout","ViewGroup","View"},
 "com.google.android.material.floatingactionbutton.FloatingActionButton":
               {"FloatingActionButton","ImageView","View"},
 "systems.sieber.fsclock.FitPreviewView": {"FitPreviewView","View"},
 "systems.sieber.fsclock.FsClockView":    {"FsClockView","View"},
 "androidx.appcompat.widget.Toolbar":     {"Toolbar","ViewGroup","View"},
 "HorizontalScrollView": {"HorizontalScrollView","FrameLayout","ViewGroup","View"},
 "ScrollView": {"ScrollView","FrameLayout","ViewGroup","View"},
 "Space": {"Space","View"},
 "ImageButton": {"ImageButton","ImageView","View"},
}

# field name -> declared java type, per file
bad = 0
for jp in glob.glob("source/app/src/main/java/systems/sieber/fsclock/*.java"):
    src = open(jp, encoding="utf-8").read()
    types = {}
    for m in re.finditer(r'^\s*(?:private |public |protected |static |final )*'
                         r'([A-Za-z_][\w.]*)\s+(m?[A-Za-z_]\w*)\s*;', src, re.M):
        types[m.group(2)] = m.group(1).split(".")[-1]
    # holder.x = findViewById(R.id.y)  /  mFoo = findViewById(R.id.y)
    for m in re.finditer(r'(?:(\w+)\.)?(\w+)\s*=\s*(?:\w+\.)?findViewById\(R\.id\.(\w+)\)', src):
        field, rid = m.group(2), m.group(3)
        jtype = types.get(field)
        if not jtype or rid not in decl: continue
        for tag in decl[rid]:
            allowed = OK.get(tag)
            if allowed is None: continue
            if jtype not in allowed and jtype != "View" and jtype != "Object":
                line = src[:m.start()].count("\n") + 1
                print("!! %s:%d  %s is %s in Java but <%s> in XML (id=%s)"
                      % (os.path.basename(jp), line, field, jtype, tag, rid))
                bad += 1
print("\n%d type mismatch(es)" % bad)
