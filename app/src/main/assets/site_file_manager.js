(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login");
  const workspace = $("workspace");
  const keyInput = $("api-key");
  const loginMessage = $("login-message");
  const siteSelect = $("site-select");
  const entriesList = $("entries");
  const editor = $("editor");
  const editorName = $("editor-name");
  const content = $("content");
  const message = $("message");
  let apiKey = "";
  let siteId = "";
  let pathParts = [];
  let selectedPath = "";

  function currentPath() { return pathParts.join("/"); }
  function setMessage(text, error = false) {
    message.textContent = text;
    message.classList.toggle("error", error);
  }
  function hideEditor() {
    selectedPath = "";
    editor.hidden = true;
    content.value = "";
  }
  async function request(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {}),
      },
    });
    if (response.status === 401) {
      logout("Anahtar doğrulanamadı. API anahtarınızı yeniden girin.");
      throw new Error("unauthorized");
    }
    if (!response.ok) {
      let detail = `İstek başarısız (${response.status}).`;
      try { detail = (await response.json()).error || detail; } catch (_) { /* keep generic error */ }
      throw new Error(detail);
    }
    if (response.status === 204 || response.status === 201) return null;
    return response.json();
  }
  function fileApi(site, resource, path) {
    const query = new URLSearchParams();
    if (path) query.set("path", path);
    const encodedQuery = query.toString();
    return `/_pocketpaas/files/sites/${encodeURIComponent(site)}/${resource}${encodedQuery ? `?${encodedQuery}` : ""}`;
  }
  function logout(note = "") {
    apiKey = "";
    siteId = "";
    pathParts = [];
    hideEditor();
    workspace.hidden = true;
    login.hidden = false;
    keyInput.value = "";
    loginMessage.textContent = note;
    loginMessage.classList.toggle("error", Boolean(note));
  }
  async function connect() {
    const sites = await request("/_pocketpaas/files/sites");
    siteSelect.replaceChildren();
    for (const site of sites) {
      const option = document.createElement("option");
      option.value = String(site.id);
      option.textContent = site.domainName;
      siteSelect.append(option);
    }
    if (sites.length === 0) throw new Error("Henüz kayıtlı bir site yok.");
    login.hidden = true;
    workspace.hidden = false;
    siteId = siteSelect.value;
    await refreshEntries();
  }
  async function refreshEntries() {
    hideEditor();
    const query = new URLSearchParams();
    const path = currentPath();
    if (path) query.set("path", path);
    $("path-label").textContent = `/${path}`;
    $("up").disabled = pathParts.length === 0;
    entriesList.replaceChildren();
    setMessage("Dosyalar yükleniyor…");
    try {
      const encodedQuery = query.toString();
      const data = await request(`/_pocketpaas/files/sites/${encodeURIComponent(siteId)}/entries${encodedQuery ? `?${encodedQuery}` : ""}`);
      for (const entry of data.entries) {
        const item = document.createElement("li");
        item.className = "entry";
        const open = document.createElement("button");
        open.type = "button";
        open.textContent = `${entry.isDirectory ? "📁" : "📄"} ${entry.name}`;
        open.addEventListener("click", () => entry.isDirectory ? openDirectory(entry.name) : openFile(entry.name));
        item.append(open);
        if (!entry.isDirectory) {
          const size = document.createElement("small");
          size.textContent = entry.sizeBytes == null ? "metin" : `${entry.sizeBytes} bayt`;
          item.append(size);
        }
        entriesList.append(item);
      }
      setMessage(data.entries.length ? "" : "Bu klasör boş.");
    } catch (error) {
      setMessage(`Dosyalar yüklenemedi: ${error.message}`, true);
    }
  }
  async function openDirectory(name) {
    pathParts.push(name);
    await refreshEntries();
  }
  async function openFile(name) {
    const path = [currentPath(), name].filter(Boolean).join("/");
    setMessage("Dosya açılıyor…");
    try {
      const result = await request(fileApi(siteId, "file", path));
      selectedPath = path;
      editorName.textContent = path;
      content.value = result.content;
      editor.hidden = false;
      setMessage("");
    } catch (error) {
      setMessage(`Dosya açılamadı: ${error.message}`, true);
    }
  }
  async function saveFile(path = selectedPath, text = content.value) {
    const bytes = new TextEncoder().encode(text).length;
    if (bytes > 32768) { setMessage("Dosya 32 KiB sınırını aşıyor.", true); return; }
    await request(fileApi(siteId, "file"), {
      method: "PUT",
      body: JSON.stringify({ path, content: text }),
    });
    await refreshEntries();
    if (path) await openFile(path.split("/").pop());
    setMessage("Dosya kaydedildi.");
  }

  $("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const candidate = keyInput.value.trim();
    if (!/^[a-f0-9]{64}$/.test(candidate)) {
      loginMessage.textContent = "API anahtarı 64 küçük harfli onaltılık karakter olmalıdır.";
      loginMessage.classList.add("error");
      return;
    }
    loginMessage.textContent = "";
    loginMessage.classList.remove("error");
    apiKey = candidate;
    try { await connect(); } catch (error) { logout(`Bağlantı kurulamadı: ${error.message}`); }
  });
  siteSelect.addEventListener("change", async () => {
    siteId = siteSelect.value;
    pathParts = [];
    await refreshEntries();
  });
  $("refresh").addEventListener("click", refreshEntries);
  $("logout").addEventListener("click", () => logout("Bağlantı kapatıldı."));
  $("up").addEventListener("click", async () => { pathParts.pop(); await refreshEntries(); });
  $("save").addEventListener("click", async () => {
    try { await saveFile(); } catch (error) { setMessage(`Kaydetme başarısız: ${error.message}`, true); }
  });
  $("delete").addEventListener("click", async () => {
    if (!selectedPath || !window.confirm(`“${selectedPath}” dosyası silinsin mi?`)) return;
    try {
      await request(fileApi(siteId, "file", selectedPath), { method: "DELETE" });
      hideEditor();
      await refreshEntries();
      setMessage("Dosya silindi.");
    } catch (error) { setMessage(`Silme başarısız: ${error.message}`, true); }
  });
  $("new-file").addEventListener("click", async () => {
    const name = window.prompt("Yeni metin dosyasının adı (gizli dosyalar kapalıdır):");
    if (!name) return;
    const path = [currentPath(), name].filter(Boolean).join("/");
    try { await saveFile(path, ""); } catch (error) { setMessage(`Dosya oluşturulamadı: ${error.message}`, true); }
  });
  $("new-folder").addEventListener("click", async () => {
    const name = window.prompt("Yeni klasörün adı:");
    if (!name) return;
    const path = [currentPath(), name].filter(Boolean).join("/");
    try {
      await request(fileApi(siteId, "directory"), { method: "POST", body: JSON.stringify({ path }) });
      await refreshEntries();
      setMessage("Klasör oluşturuldu.");
    } catch (error) { setMessage(`Klasör oluşturulamadı: ${error.message}`, true); }
  });
})();
