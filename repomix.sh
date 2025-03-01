#!/bin/bash

#
# Copyright 2022 - 2025 The Original Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
#     you may not use this file except in compliance with the License.
#     You may obtain a copy of the License at
#
#           http://www.apache.org/licenses/LICENSE-2.0
#
#     Unless required by applicable law or agreed to in writing, software
#     distributed under the License is distributed on an "AS IS" BASIS,
#     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#     See the License for the specific language governing permissions and
#     limitations under the License.
#
#

# Directories to iterate over
directories=("main" "services" "test-apps")

# Output directory for repomix files
output_dir="repomix"

# Create the output directory if it doesn't exist
mkdir -p "$output_dir"

# Iterate over each directory
for dir in "${directories[@]}"; do
  if [ -d "$dir" ]; then
    # Generate repomix file for the current directory
    repomix_file="$output_dir/$(basename "$dir").txt"
    repomix --config repomix.config.json --output "$repomix_file" "$dir"
  else
    echo "Directory $dir does not exist."
  fi
done