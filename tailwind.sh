#!/bin/bash

set -eo pipefail

#npx tailwindcss -i ./src/main/resources/index.css -o ./src/main/resources/assets/index.css --watch
npx tailwindcss -i ./src/main/resources/index.css -o ./src/main/resources/assets/index.min.css -m
