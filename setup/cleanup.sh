#!/bin/bash

for dir in server*/
do
  rm -rf "$dir"
done

rm -rf "client/"