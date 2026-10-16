#!/usr/bin/env python3
"""Checks APK's actual ELF chain, not intermediate build libraries."""
import pathlib, subprocess, sys, re
folder, readelf = pathlib.Path(sys.argv[1]), sys.argv[2]
libs = {p.name:p for p in folder.glob('*.so')}
assert 'libjamesdsp.so' in libs and 'libjamesdsp-wrapper.so' in libs
symbols = {}
needs = {}
for name,p in libs.items():
    hdr = subprocess.check_output([readelf,'-h',str(p)],text=True)
    assert 'AArch64' in hdr and 'ELF64' in hdr, name
    dyn = subprocess.check_output([readelf,'-d',str(p)],text=True)
    needs[name] = re.findall(r'\(NEEDED\).*?\[(.*?)\]',dyn)
    sym = subprocess.check_output([readelf,'--dyn-syms','--wide',str(p)],text=True)
    exported, imported = set(), set()
    for line in sym.splitlines():
        fields=line.split()
        if len(fields)>=8 and fields[0].rstrip(':').isdigit():
            symbol=fields[7].split('@')[0]
            if fields[6]=='UND' and fields[4]!='WEAK': imported.add(symbol)
            elif fields[6]!='UND' and fields[4] in ('GLOBAL','WEAK'): exported.add(symbol)
    symbols[name]=(exported,imported)
    print(name,'dependencies=',needs[name])
    for dep in needs[name]:
        assert dep in libs or dep in {'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so'},(name,dep)
assert 'JamesDSPGlobalMemoryAllocation' in symbols['libjamesdsp.so'][0]
assert 'libjamesdsp.so' in needs['libjamesdsp-wrapper.so']
for name,(_,imports) in symbols.items():
    for symbol in imports:
        if symbol.startswith(('JamesDSP','JLimiter','EELStdOut')):
            assert any(symbol in symbols[d][0] for d in needs[name] if d in symbols),(name,symbol)
print('ARM64 architecture, packaged dependency closure, mandatory DSP exports and wrapper dependency: PASS')
print('No claim of device playback or installation verification.')
