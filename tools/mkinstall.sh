#!/bin/sh
# Make an image somebody can write to a disk and boot.
#
# Everything else in this build produces something to run under a hypervisor,
# which is the right thing while a feature is being written and the wrong thing
# when the question is whether this machine boots. This produces one file:
# GPT-partitioned, with an EFI system partition carrying the loader and a
# second partition for the document, which is the layout firmware looks for and
# the layout kernel/store.S already reads.
#
# Written to a disk with dd, or attached to a virtual machine, or copied to a
# USB stick. The same bytes in all three cases, which is the point - a machine
# that boots this from a stick is running what a machine that boots it from its
# own disk runs.
#
#   tools/mkinstall.sh <loader.efi> <out.img> [megabytes]
#
# POSIX sh and the tools every Unix has, rather than a partitioning library:
# this has to run on the machine somebody is porting from, and that machine is
# not guaranteed to have anything installed.
set -eu

LOADER=${1:?give it the loader: tools/mkinstall.sh build/BOOTAA64.EFI build/maos.img}
OUT=${2:?give it an output path}
MB=${3:-256}

ESP_MB=64
ESP="$OUT.esp"

# ---- the EFI system partition, which is where firmware looks and nowhere else
#
# FAT32 rather than FAT16, because some firmware will not look at the second.
# 512-byte sectors because the GPT below assumes them and a mismatch produces a
# disk that looks empty to the firmware and fine to everything else.
rm -f "$ESP"
dd if=/dev/zero of="$ESP" bs=1048576 count="$ESP_MB" status=none

if command -v mformat >/dev/null 2>&1; then
    mformat -i "$ESP" -F -v MAOS ::
    mmd -i "$ESP" ::/EFI ::/EFI/BOOT
    mcopy -i "$ESP" "$LOADER" ::/EFI/BOOT/BOOTAA64.EFI
elif command -v hdiutil >/dev/null 2>&1; then
    # macOS has no mtools by default, and newfs_msdos will not format a plain
    # file - it asks the thing it is given for a partition offset. So the file
    # is attached as a device first, formatted, mounted and filled. Nothing to
    # install, which is the point: whoever is making an installer should not
    # have to set up a toolchain to do it.
    DEV=$(hdiutil attach -nomount -imagekey diskimage-class=CRawDiskImage "$ESP" | head -1 | tr -d '[:space:]')
    trap 'hdiutil detach "$DEV" >/dev/null 2>&1 || true' EXIT
    newfs_msdos -F 32 -v MAOS "$DEV" >/dev/null
    MNT=$(mktemp -d)
    mount -t msdos "$DEV" "$MNT"
    mkdir -p "$MNT/EFI/BOOT"
    cp "$LOADER" "$MNT/EFI/BOOT/BOOTAA64.EFI"
    sync
    umount "$MNT"
    rmdir "$MNT"
    hdiutil detach "$DEV" >/dev/null
    trap - EXIT
else
    echo "no way to make a FAT volume here: install mtools, or run this on macOS" >&2
    exit 1
fi

# ---- the whole disk: a protective MBR, a GPT, and the two partitions
#
# Built here rather than with parted or gdisk for the reason given above, and
# because a GPT is two headers, an array of entries and three CRCs. That is a
# page of Python and no dependency, against a tool that may not be installed on
# the machine somebody is making an installer from.
python3 - "$OUT" "$ESP" "$MB" "$ESP_MB" <<'PY'
import os, struct, sys, uuid, zlib

out, esp, total_mb, esp_mb = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
SECTOR = 512
total = total_mb * 1024 * 1024 // SECTOR
first_usable = 34
esp_start = 2048
esp_sectors = esp_mb * 1024 * 1024 // SECTOR
doc_start = esp_start + esp_sectors
last_usable = total - 34
doc_sectors = last_usable - doc_start + 1
if doc_sectors < 2048:
    sys.exit("that disk is too small to hold the document partition as well")

ESP_TYPE = uuid.UUID("c12a7328-f81f-11d2-ba4b-00a0c93ec93b")
DATA_TYPE = uuid.UUID("0fc63daf-8483-4772-8e79-3d69d8477de4")

def entry(type_uuid, start, end, name):
    return (type_uuid.bytes_le + uuid.uuid4().bytes_le
            + struct.pack("<QQQ", start, end, 0)
            + name.encode("utf-16-le").ljust(72, b"\0")[:72])

entries = (entry(ESP_TYPE, esp_start, esp_start + esp_sectors - 1, "MAOS EFI")
           + entry(DATA_TYPE, doc_start, doc_start + doc_sectors - 1, "MAOS DOCUMENT"))
entries = entries.ljust(128 * 128, b"\0")
entries_crc = zlib.crc32(entries) & 0xFFFFFFFF
disk_guid = uuid.uuid4()

def header(current, backup, entries_lba):
    h = bytearray(92)
    h[0:8] = b"EFI PART"
    struct.pack_into("<IIII", h, 8, 0x00010000, 92, 0, 0)
    struct.pack_into("<QQQQ", h, 24, current, backup, first_usable, last_usable)
    h[56:72] = disk_guid.bytes_le
    struct.pack_into("<QIII", h, 72, entries_lba, 128, 128, entries_crc)
    struct.pack_into("<I", h, 16, zlib.crc32(bytes(h)) & 0xFFFFFFFF)
    return bytes(h).ljust(SECTOR, b"\0")

with open(out, "wb") as f:
    f.truncate(total * SECTOR)

    # A protective MBR, so a tool that only understands MBR sees one full
    # partition it does not recognise rather than an empty disk it offers to
    # format.
    mbr = bytearray(SECTOR)
    mbr[446:462] = (b"\x00\x00\x02\x00\xee\xff\xff\xff"
                    + struct.pack("<II", 1, min(total - 1, 0xFFFFFFFF)))
    mbr[510:512] = b"\x55\xaa"
    f.write(mbr)

    f.write(header(1, total - 1, 2))
    f.write(entries)

    f.seek(esp_start * SECTOR)
    with open(esp, "rb") as e:
        while True:
            chunk = e.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)

    # The backup, which is what firmware falls back to and what makes a disk
    # that was written with dd and interrupted recoverable rather than a brick.
    f.seek((total - 33) * SECTOR)
    f.write(entries)
    f.seek((total - 1) * SECTOR)
    f.write(header(total - 1, 1, total - 33))

print("    %s: %d MB, EFI at sector %d, document at sector %d"
      % (out, total_mb, esp_start, doc_start))
PY

rm -f "$ESP"
