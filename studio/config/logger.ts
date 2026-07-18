import { format, transports } from 'winston';

export default {
    level: process.env.STRAPI_LOG_LEVEL || 'info',
    format: format.printf(({ level, message }) => {
        // This gives you: "info: Strapi started successfully"
        // Azure adds the date and time automatically on the left.
        return `${level}: ${message}`;
    }),
    transports: [
        new transports.Console() // Instantiate the actual transport
    ],
};