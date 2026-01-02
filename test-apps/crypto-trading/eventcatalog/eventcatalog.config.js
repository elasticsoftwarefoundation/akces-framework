/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

/** @type {import('@eventcatalog/core/bin/eventcatalog.config').Config} */
    export default {
      title: 'AKCES Framework - Crypto Trading Test Application',
      tagline: 'Explore the event-driven architecture of the AKCES Framework, providing comprehensive documentation for our event sourcing patterns, commands, and domain models',
      organizationName: 'Elastic Software Foundation',
      homepageLink: 'https://github.com/elasticsoftwarefoundation/akces-framework/',
      editUrl: 'https://github.com/elasticsoftwarefoundation/akces-framework/edit/master',
      // By default set to false, add true to get urls ending in /
      trailingSlash: true,
      // Base URL for the site
      base: '/akces-framework/',
      // Customize the logo
      logo: {
        alt: 'AKCES Framework Logo',
        src: '/logo.png',
        text: 'AKCES Framework'
      },
      docs: {
        sidebar: {
          // Using a list view for better API documentation style presentation
          type: 'LIST_VIEW'
        },
      },
      // Enable RSS feed
      rss: {
        enabled: true,
        limit: 20
      },
      // Required random generated id used by eventcatalog
      cId: 'e346d584-c5a6-4c7f-94d1-a2648bd4a6c1',
      // Additional AKCES-specific settings
      navbar: {
        items: [
          {
            label: 'Documentation',
            to: '/docs',
          },
          {
            label: 'Events',
            to: '/events',
          },
          {
            label: 'Services',
            to: '/services',
          },
          {
            label: 'Teams',
            to: '/teams',
          },
          {
            label: 'GitHub',
            href: 'https://github.com/elasticsoftwarefoundation/akces-framework/',
          },
        ],
      },
      footer: {
        copyright: `Copyright © ${new Date().getFullYear()} Elastic Software Foundation. Built with EventCatalog.`,
      }
    }