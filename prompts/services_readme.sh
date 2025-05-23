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
cat repomix/services.txt | llm "Based on the codebase in this file, generate a SERVICES.md file that contains information on the services. The Akces Framework is an CQRS & Event Sourcing Framework built on Apache Kafka. Take in consideration the current contents of the SERVICE.md file and make incremental updates." -m anthropic/claude-3-7-sonnet-latest > SERVICES.md