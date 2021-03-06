#!/bin/sh

log() { echo "$@" 1>&2; }
fail() { log "$@" " ($?)"; exit 1; }

# Change directory to the main folder
cd "$(dirname "$0")/.." || fail "Failed initial dir change"

wemi_home="$(pwd)"
log "Working from home: $wemi_home"

wemi_version=$(./wemi --machine-readable-output=shell wemi/projectVersion) || fail "wemi (version)"
log "Wemi version: $wemi_version"

task="wemi/distributionArchive skipTest=false"
if [ "$1" = "--skipTest" ]; then
	task="wemi/distributionArchive skipTest=true"
fi

# Build the archive files in Wemi
./wemi clean || fail "wemi (clean)"
wemi_dist_dir=$(./wemi --machine-readable-output=shell "$task") || fail "wemi (archive)"

# Build IDE plugins
wemi_intellij_plugin_zip=$(./ide-plugins/wemi --machine-readable-output=shell "ideIntellij/intellijPluginArchive") || fail "wemi IDE Intellij"
cp "$wemi_intellij_plugin_zip" "$wemi_dist_dir/WemiForIntelliJ.zip" || fail "cp $wemi_intellij_plugin_zip -> $wemi_dist_dir"

if [ "$1" = "--skipTest" ]; then
	exit 0
fi

# Allow only reading - unless it's a snapshot, which can be overwritten
chmod_mode="0444"
if [ "${wemi_version%-SNAPSHOT}" != "${wemi_version}" ]; then
	# Snapshot
	chmod_mode="0644"
	sftp -P 1022 -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || log "Snapshot version directory already exists"
	mkdir ./${wemi_version}
END_SFTP
else
	# Normal release
	sftp -P 1022 -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || fail "Version directory must not exist"
	mkdir ./${wemi_version}
END_SFTP
fi

sftp -P 1022 -b - -f "sftp://wemi@darkyen.com/wemi-versions/" <<-END_SFTP || fail "Failed to upload new Wemi version"
	put ${wemi_dist_dir}/* ./${wemi_version}/
	chmod ${chmod_mode} ./${wemi_version}/*
END_SFTP

log "Done publishing Wemi version $wemi_version"