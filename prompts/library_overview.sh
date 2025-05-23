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

cat repomix/main.txt | llm "This file contains the entire codebase of the Akces Framework. Please provide a comprehensive overview of the library, including its main purpose, key features, and overall architecture. Take into consideration the current contents of the FRAMEWORK_OVERVIEW.md file and make incremental updates" -m anthropic/claude-3-7-sonnet-latest > FRAMEWORK_OVERVIEW.md