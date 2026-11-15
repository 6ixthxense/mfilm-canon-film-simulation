#!/bin/sh
# the phone's way in (fos_dev_start_raw) against the CR2's: the same mosaic
# must give the same print, byte for byte, and the same JPEG
cd "$(dirname "$0")"
set -e
T=out/rawin
mkdir -p $T
fail=0
check() {
    name=$1; shift
    ./out/mkcr2 $T/$name.CR2 "$@" >/dev/null
    for size in 0 2; do
        FOS_MIX=1 FOS_SIZE=$size FOS_JPEG=$T/a.JPG ./out/devtest $T/$name.CR2 $T/a.FOP >/dev/null 2>&1
        for via in 1 shift; do
            rm -f $T/b.FOP $T/b.JPG
            FOS_MIX=1 FOS_SIZE=$size FOS_JPEG=$T/b.JPG FOS_VIA_RAW=$via ./out/devtest $T/$name.CR2 $T/b.FOP >/dev/null 2>&1
            if cmp -s $T/a.FOP $T/b.FOP && cmp -s $T/a.JPG $T/b.JPG; then echo "same: $name size $size ($via)"
            else echo "DIFFERENT: $name size $size ($via)"; fail=1; fi
        done
    done
}
check eosm
check portrait --portrait
check street --scene street --wb 2.1,1.5
check fourcomp --clrs 4
check sliced --slices 2,1728,1824
check 5d2 --sensor 5792,3804,160,51
check blown --ev 2 --white 13900
exit $fail
