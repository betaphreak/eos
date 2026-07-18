import type { Core } from '@strapi/strapi';

const config: Core.Config.Api = {
  rest: {
    defaultLimit: 500,
    maxLimit: 10000,
    withCount: true,
  },
};

export default config;
