export default (plugin: any) => {
  // Save the original upload method
  const originalUpload = plugin.services.upload.upload;

  // Override it with our custom logic
  plugin.services.upload.upload = async ({ data, files }: any) => {
    // Ensure files is always an array so we can iterate over it
    const filesArray = Array.isArray(files) ? files : [files];

    filesArray.forEach((file: any) => {
      // Get the original file name without the extension
      const originalName = file.name.substring(0, file.name.lastIndexOf('.'));

      // Sanitize the name to make it URL-safe (replace spaces/special chars with underscores)
      const safeName = originalName.replace(/[^a-zA-Z0-9-_]/g, '_').toLowerCase();

      // Strapi builds the final URL using `${file.hash}${file.ext}`.
      // By overwriting the hash, we force it to use our safe original name!
      file.hash = safeName;
    });

    // Proceed with the standard upload process using our modified files
    return originalUpload({ data, files });
  };

  return plugin;
};