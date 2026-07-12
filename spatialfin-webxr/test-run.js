import { pathToFileURL } from 'url';
import path from 'path';

const indexPath = path.resolve('../docs/web/assets/index-DofvxvMy.js');
import(pathToFileURL(indexPath).href).then(() => {
  console.log("Module loaded successfully");
}).catch(err => {
  console.error("Error loading module:", err);
});
