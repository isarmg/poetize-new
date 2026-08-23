function getError(action, xhr) {
  let msg;
  if (xhr.response) {
    msg = `${xhr.response.error || xhr.response}`;
  } else if (xhr.responseText) {
    msg = `${xhr.responseText}`;
  } else {
    msg = `fail to ${action} ${xhr.status}`;
  }

  return new Error(msg);
}

function getBody(xhr) {
  const text = xhr.responseText || xhr.response;
  if (!text) {
    return text;
  }

  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export default function upload(option) {
  const xhr = new XMLHttpRequest();
  const action = option.action;
  return new Promise((resolve, reject) => {
    if (xhr.upload) {
      xhr.upload.onprogress = function progress(e) {
        if (e.total > 0) {
          e.percent = e.loaded / e.total * 100;
        }
        option.onProgress(e);
      };
    }

    const formData = new FormData();

    if (option.data) {
      Object.keys(option.data).forEach(key => {
        const value = option.data[key];
        if (Array.isArray(value)) {
          value.forEach(item => formData.append(key, item));
        } else if (value !== undefined && value !== null) {
          formData.append(key, value);
        }
      });
    }

    formData.append(option.filename, option.file, option.file.name);

    xhr.onerror = function error() {
      reject(getError(action, xhr));
    };

    xhr.onabort = function abort() {
      reject(new Error(`upload to ${action} was aborted`));
    };

    xhr.onload = function onload() {
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(getError(action, xhr));
        return;
      }

      resolve(getBody(xhr));
    };

    xhr.open('post', action, true);

    if (option.withCredentials && 'withCredentials' in xhr) {
      xhr.withCredentials = true;
    }

    const headers = option.headers || {};

    for (const item in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, item) && headers[item] !== null) {
        xhr.setRequestHeader(item, headers[item]);
      }
    }
    xhr.send(formData);
  });
}
