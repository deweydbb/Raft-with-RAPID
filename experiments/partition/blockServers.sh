#!/bin/bash
cd "$(dirname "$0")" || exit

./deleteAllBlocked.sh

while [[ $# -gt 0 ]]; do
  case $1 in
    --file)
      FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--file Required. Specifies a file containing a list on hosts, one on each line to run addServer on\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [ -z ${FILE+x} ]; then
  echo "--file is required"
  exit
fi

# get private ip address of the system
IP=$(ifconfig | grep -Eo 'inet (addr:)?([0-9]*\.){3}[0-9]*' | grep -Eo '([0-9]*\.){3}[0-9]*' | grep -v '127.0.0.1')

# find line in file that starts with systems ip address and get rest of the remaining line
BLOCK_IPS=$(cat "$FILE" | grep "^$IP" | grep -Eo "\s.*$")

for HOST in $BLOCK_IPS; do
    echo "blocking $HOST"
    sudo iptables -I INPUT -s "$HOST" -j DROP
done