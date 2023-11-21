#!/bin/bash

NEW_TERMINAL=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --host)
      HOST="$2"
      shift # past argument
      shift # past value
      ;;
    -t|--terminal)
      NEW_TERMINAL=true
      shift # past argument
    ;;
    --help)
      echo "Options:"
      printf "--host Required. Specifies the remote host to run getJar on"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [ -z ${HOST+x} ]; then
  echo "--host is required"
  exit
fi

if [ "$NEW_TERMINAL" = true ]; then
  gnome-terminal --title="$HOST:getJar" -- "/usr/bin/ssh" "davidk@${HOST}" -t "~/Desktop/Distributed/jraft/experiments/getJar.sh"
else
  ssh "davidk@$HOST" -t "~/Desktop/Distributed/jraft/experiments/getJar.sh"
fi



