"""Pre-push check for uncompiled branches: flag Kotlin identifiers that nothing resolves.

Usage:
  python -I tools/lint/import_scan.py [--base origin/main] [file.kt ...]

With no files, scans the .kt files changed on this branch since --base (default origin/main)
plus uncommitted changes. For each file, a capitalized identifier is "unresolved" when it is not:
  - imported (simple name or `as` alias),
  - declared in the same package directory (class/object/interface/typealias, enum entries),
  - a Kotlin/JDK builtin from the list below.
Strings and comments are ignored. Exit code 1 when anything is flagged.

It is a heuristic, not a compiler: java.lang names and nested types reached through their outer
class can show up as false positives; a clean run doesn't prove the code compiles. It does catch
the common bounce: a new file missing an import every sibling file has (e.g. ScriptContext).
"""
import argparse
import os
import re
import subprocess
import sys

BUILTIN = set(
    """Int Long Boolean String Unit Any Nothing Double Float Short Byte Char List MutableList Map
    MutableMap Set MutableSet HashMap HashSet LinkedHashMap LinkedHashSet ArrayList ArrayDeque Pair
    Triple Array IntArray LongArray ByteArray BooleanArray IntRange LongRange CharRange Sequence
    Iterable Iterator Collection MutableCollection Comparator Comparable CharSequence Number Enum
    Exception RuntimeException IllegalArgumentException IllegalStateException
    NoSuchElementException IndexOutOfBoundsException UnsupportedOperationException
    NotImplementedError Error Throwable Override Suppress OptIn Deprecated Volatile JvmStatic
    JvmField JvmOverloads Synchronized Inject Singleton Regex StringBuilder Lazy Result Math
    System Thread Runnable KProperty KClass Companion T R K V E""".split()
)
# Kotlin default imports (kotlin.*, .annotation, .collections, .comparisons, .io, .ranges,
# .sequences, .text) and java.lang.* names that turn up in this codebase.
BUILTIN |= set(
    """RegexOption MatchResult LazyThreadSafetyMode Throws Annotation Target Retention
    AnnotationTarget AnnotationRetention Repeatable MustBeDocumented ReplaceWith DeprecationLevel
    PublishedApi DslMarker Experimental RequiresOptIn SinceKotlin UnsafeVariance Function
    Function0 Function1 Function2 KotlinVersion UninitializedPropertyAccessException
    ArithmeticException ClassCastException NumberFormatException ConcurrentModificationException
    AbstractList AbstractMap AbstractSet AbstractCollection MutableIterator ListIterator
    Grouping IndexedValue Map.Entry Integer Character Object Class ClassLoader Process
    StackTraceElement InterruptedException CloneNotSupportedException AutoCloseable Appendable
    Cloneable Readable ThreadLocal Void StringBuffer Runtime SecurityException Deprecated
    StringBuilder Charsets""".split()
)

STRING = re.compile(r'"""(?:.|\n)*?"""|"(?:[^"\\\n]|\\.)*"')
DECLARED = re.compile(r"\b(?:class|object|interface|typealias)\s+(\w+)")
ENUM_ENTRY = re.compile(r"^\s+([A-Z]\w*)\s*(?:\(|,|;|$|\{)", re.M)


def strip(src):
    body = STRING.sub('""', src)
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//.*", "", body)
    return re.sub(r"^(?:import|package) .*$", "", body, flags=re.M)


def package_names(pkgdir):
    names = set()
    for name in os.listdir(pkgdir):
        if not name.endswith(".kt"):
            continue
        text = open(os.path.join(pkgdir, name), encoding="utf-8").read()
        names |= set(DECLARED.findall(text))
        for block in re.findall(r"enum class \w+[^{]*\{(.*?)(?:;|\n\s*\})", text, re.S):
            names |= set(ENUM_ENTRY.findall(block))
    return names


def scan(path):
    src = open(path, encoding="utf-8").read()
    imported = {m.split(".")[-1] for m in re.findall(r"^import ([\w.]+)\s*$", src, re.M)}
    imported |= set(re.findall(r"^import [\w.]+ as (\w+)", src, re.M))
    declared = package_names(os.path.dirname(os.path.abspath(path)))
    used = set(re.findall(r"(?<![\w.])([A-Z]\w*)", strip(src)))
    return sorted(u for u in used - imported - declared - BUILTIN if not u.isupper())


def changed_files(base):
    def git(*args):
        out = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
        return out.stdout.split()

    files = git("diff", "--name-only", f"{base}...HEAD") + git("diff", "--name-only")
    return sorted({f for f in files if f.endswith(".kt") and os.path.exists(f)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("files", nargs="*")
    args = parser.parse_args()
    files = args.files or changed_files(args.base)
    flagged = 0
    for path in files:
        missing = scan(path)
        if missing:
            flagged += 1
            print(f"{path}: {', '.join(missing)}")
    print(f"import_scan: {len(files)} files, {flagged} flagged", file=sys.stderr)
    return 1 if flagged else 0


if __name__ == "__main__":
    sys.exit(main())
