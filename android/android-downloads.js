(function () {
  if (window.androidDownloadInstalled) return;
  window.androidDownloadInstalled = true;

  window.androidSaveDownload = function (url, name) {
    fetch(url).then(function (response) {
      if (!response.ok) throw new Error('Download failed');
      return response.blob();
    }).then(function (blob) {
      if (blob.size > 24 * 1024 * 1024) {
        AndroidExport.showError();
        return;
      }
      const reader = new FileReader();
      reader.onerror = function () { AndroidExport.showError(); };
      reader.onload = function () {
        const result = String(reader.result);
        AndroidExport.save(name, blob.type || 'application/octet-stream', result.slice(result.indexOf(',') + 1));
      };
      reader.readAsDataURL(blob);
    }).catch(function () { AndroidExport.showError(); });
  };

  document.addEventListener('click', function (event) {
    if (!(event.target instanceof Element)) return;
    const link = event.target.closest('a[download]');
    if (!link || !/^(blob:|data:)/.test(link.href)) return;
    event.preventDefault();
    window.androidSaveDownload(link.href, link.download);
  }, true);
})();
