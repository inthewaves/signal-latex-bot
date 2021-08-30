#!/bin/bash

set -o errexit

source .env

./gradlew clean build distTar

output_dist_dir=build/distributions/signal-latex-bot

tar -xf build/distributions/*.tar --one-top-level=$output_dist_dir --strip-components 1

remote="$DEPLOY_REMOTE"
path=/opt/signallatexbot
target=$path/deploy

echo
echo deploying to $target at "$remote"
echo

ssh "$remote" rm -rf $target
rsync -rpcv --chmod=D755,F644 --delete $output_dist_dir/lib "$remote:$target"
# rsync -rpcv --chmod=D755,F755 --delete $output_dist_dir/bin $remote:$target
ssh "$remote" sync -f $target
ssh "$remote" sync .
ssh "$remote" systemctl daemon-reload
ssh "$remote" systemctl restart signallatexbot

echo
echo deployed
