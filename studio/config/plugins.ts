import type { Core } from '@strapi/strapi';

const config = ({ env }: Core.Config.Shared.ConfigParams): any => {

    // Extract variables with fallbacks to avoid ".trim() of undefined" errors during build
    const account = env('STORAGE_ACCOUNT') || '';
    const accountKey = env('STORAGE_ACCOUNT_KEY') || '';

    return {
        upload: {
            config: {
                security: {
                    allowedTypes: [
                        'image/*',
                        'video/*',
                        'application/pdf',
                        'application/json',
                        'text/plain'
                    ],
                    deniedTypes: [
                        'application/x-sh',
                        'application/x-dosexec'
                    ]
                },
                provider: 'strapi-provider-upload-azure-sa',
                providerOptions: {
                    account: account.trim(),
                    accountKey: accountKey.trim(),
                    containerName: 'media',
                    defaultPath: 'strapi',
                    baseUrl: 'civstudiostore.blob.core.windows.net',
                    maxConcurrent: 10
                },
                actionOptions: {
                    upload: {},
                    uploadStream: {},
                    delete: {},
                },
            },
        },
    };
};

export default config;