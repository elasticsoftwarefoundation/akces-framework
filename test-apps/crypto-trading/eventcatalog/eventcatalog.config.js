/** @type {import('@eventcatalog/core/bin/eventcatalog.config').Config} */
    export default {
      title: 'AKCES Framework - Crypto Trading Test Application',
      tagline: 'Explore the event-driven architecture of the AKCES Framework, providing comprehensive documentation for our event sourcing patterns, commands, and domain models',
      organizationName: 'Elastic Software Foundation',
      homepageLink: 'https://github.com/elasticsoftwarefoundation/akces-framework/',
      editUrl: 'https://github.com/elasticsoftwarefoundation/akces-framework/edit/master',
      // By default set to false, add true to get urls ending in /
      trailingSlash: false,
      // Base URL for the site
      base: '/',
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