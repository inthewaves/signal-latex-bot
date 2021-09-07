#!/bin/bash

set -o errexit

source .env

./gradlew clean build installDist

output_dist_dir=build/install/signal-latex-bot

remote="$DEPLOY_REMOTE"
path=/opt/signallatexbot
target=$path/deploy

echo
echo deploying to $target at "$remote"
echo

rsync -rpcv --chmod=D755,F644 latex-container "$remote:$path"

rsync -rpcv --chmod=D755,F644 $output_dist_dir/lib "$remote:$target"
rsync -rpcv --chmod=D755,F755 $output_dist_dir/bin "$remote:$target"
ssh "$remote" sync -f $target
ssh "$remote" sync .
ssh "$remote" systemctl daemon-reload
ssh "$remote" systemctl restart signallatexbot

echo
echo deployed
