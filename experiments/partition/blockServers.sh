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

# ignore commented lines
LINES=$(cat "$FILE" | grep -v "^#")

# convert machine lines 
MACHINES=($(echo "$LINES" | grep -Eo "^[0-9\.]+" | tr "\n" " "))

# find our id
for INDEX in "${!MACHINES[@]}"; do 
    VAL="${MACHINES[$INDEX]}"
    if test "$IP" = "$VAL"; then
        ID=$INDEX
    fi
done

# get array of whether to a given id for this specific server
BLOCK_ARR=($(echo "$LINES" | grep "$IP" | grep -Eo "(\s[0-9])*" | grep -Eo "[0-9]" | tr "\n" " "))

for INDEX in "${!BLOCK_ARR[@]}"; do 
    SHOULD_BLOCK="${BLOCK_ARR[$INDEX]}"
    if test "$SHOULD_BLOCK" = "1"; then
        # do not block ourselves
        if [ "$INDEX" != "$ID" ]; then
            HOST_TO_BLOCK="${MACHINES[$INDEX]}"
            echo "blocking $HOST_TO_BLOCK"
            sudo iptables -I INPUT -s "$HOST_TO_BLOCK" -j DROP
        fi
    fi
done