#!/bin/bash

sudo iptables -L --line-numbers | grep -E -o ".*DROP" | grep -E -o "[0-9]+" | sort -r | while read lineNum ; do
   sudo iptables -D INPUT "$lineNum"
done