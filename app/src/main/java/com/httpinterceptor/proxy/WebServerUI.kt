package com.httpinterceptor.proxy

import android.util.Log
import com.google.gson.Gson
import com.httpinterceptor.model.*
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CopyOnWriteArrayList

class WebServerUI(
    private val port: Int,
    private val proxyService: ProxyService
) : NanoHTTPD(port) {
    
    private val gson = Gson()
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        Log.d(TAG, "Web UI request: $method $uri")
        
        return when {
            uri == "/" || uri == "/index.html" -> serveIndexPage()
            uri == "/api/requests" -> serveRequests()
            uri == "/api/rules" && method == Method.GET -> serveRules()
            uri == "/api/rules" && method == Method.POST -> createRule(session)
            uri.startsWith("/api/rules/") && method == Method.DELETE -> deleteRule(uri)
            uri.startsWith("/api/rules/") && method == Method.PUT -> updateRule(uri, session)
            uri == "/api/proxy/status" -> serveProxyStatus()
            uri == "/api/clear" && method == Method.POST -> clearRequests()
            uri.startsWith("/static/") -> serveStaticFile(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
    
    private fun serveIndexPage(): Response {
        val html = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HTTP Interceptor - Control Panel</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #333;
            min-height: 100vh;
        }
        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
        }
        header {
            background: white;
            border-radius: 12px;
            padding: 20px;
            margin-bottom: 20px;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
        }
        h1 { color: #667eea; font-size: 28px; margin-bottom: 10px; }
        .status { color: #10b981; font-weight: bold; }
        .grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin-bottom: 20px;
        }
        .panel {
            background: white;
            border-radius: 12px;
            padding: 20px;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
        }
        .panel h2 {
            color: #667eea;
            margin-bottom: 15px;
            font-size: 20px;
        }
        .request-list {
            max-height: 500px;
            overflow-y: auto;
        }
        .request-item {
            padding: 12px;
            margin-bottom: 8px;
            background: #f9fafb;
            border-radius: 8px;
            border-left: 4px solid #667eea;
            cursor: pointer;
            transition: all 0.2s;
        }
        .request-item:hover {
            background: #f3f4f6;
            transform: translateX(4px);
        }
        .method {
            display: inline-block;
            padding: 4px 8px;
            border-radius: 4px;
            font-weight: bold;
            font-size: 12px;
            color: white;
            margin-right: 8px;
        }
        .method.GET { background: #10b981; }
        .method.POST { background: #3b82f6; }
        .method.PUT { background: #f59e0b; }
        .method.DELETE { background: #ef4444; }
        .status-code {
            display: inline-block;
            font-weight: bold;
            margin-left: 8px;
        }
        .status-2xx { color: #10b981; }
        .status-3xx { color: #f59e0b; }
        .status-4xx { color: #ef4444; }
        .status-5xx { color: #8b5cf6; }
        .url {
            display: block;
            color: #6b7280;
            font-size: 14px;
            margin-top: 4px;
        }
        .rule-list {
            max-height: 500px;
            overflow-y: auto;
        }
        .rule-item {
            padding: 12px;
            margin-bottom: 8px;
            background: #f9fafb;
            border-radius: 8px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .rule-enabled { border-left: 4px solid #10b981; }
        .rule-disabled { border-left: 4px solid #9ca3af; opacity: 0.6; }
        .btn {
            padding: 8px 16px;
            border: none;
            border-radius: 6px;
            font-weight: bold;
            cursor: pointer;
            transition: all 0.2s;
        }
        .btn-primary {
            background: #667eea;
            color: white;
        }
        .btn-primary:hover {
            background: #5568d3;
        }
        .btn-danger {
            background: #ef4444;
            color: white;
        }
        .btn-danger:hover {
            background: #dc2626;
        }
        .btn-success {
            background: #10b981;
            color: white;
        }
        .btn-sm {
            padding: 4px 12px;
            font-size: 12px;
            margin-left: 4px;
        }
        .modal {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0,0,0,0.5);
            justify-content: center;
            align-items: center;
            z-index: 1000;
        }
        .modal-content {
            background: white;
            padding: 30px;
            border-radius: 12px;
            max-width: 600px;
            width: 90%;
            max-height: 80vh;
            overflow-y: auto;
        }
        .form-group {
            margin-bottom: 15px;
        }
        label {
            display: block;
            margin-bottom: 5px;
            font-weight: bold;
            color: #374151;
        }
        input, select, textarea {
            width: 100%;
            padding: 10px;
            border: 2px solid #e5e7eb;
            border-radius: 6px;
            font-size: 14px;
        }
        input:focus, select:focus, textarea:focus {
            outline: none;
            border-color: #667eea;
        }
        textarea {
            min-height: 100px;
            font-family: monospace;
        }
        pre {
            background: #1f2937;
            color: #10b981;
            padding: 15px;
            border-radius: 8px;
            overflow-x: auto;
            font-size: 12px;
        }
        .badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: bold;
        }
        .badge-info { background: #dbeafe; color: #1e40af; }
        .badge-success { background: #d1fae5; color: #065f46; }
        .badge-warning { background: #fef3c7; color: #92400e; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>🔍 HTTP Interceptor - Control Panel</h1>
            <p class="status">● Proxy Running on Port 2580</p>
            <p style="margin-top: 8px; color: #6b7280;">Capturando y modificando tráfico HTTP/HTTPS en tiempo real</p>
        </header>

        <div class="grid">
            <div class="panel">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;">
                    <h2>📡 Requests Capturados</h2>
                    <button class="btn btn-danger btn-sm" onclick="clearRequests()">Limpiar</button>
                </div>
                <div class="request-list" id="requestList">
                    <p style="color: #9ca3af; text-align: center; padding: 20px;">
                        Esperando requests...
                    </p>
                </div>
            </div>

            <div class="panel">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;">
                    <h2>⚙️ Reglas de Modificación</h2>
                    <button class="btn btn-primary btn-sm" onclick="showRuleModal()">+ Nueva Regla</button>
                </div>
                <div class="rule-list" id="ruleList">
                    <p style="color: #9ca3af; text-align: center; padding: 20px;">
                        No hay reglas configuradas
                    </p>
                </div>
            </div>
        </div>

        <div class="panel" id="detailPanel" style="display: none;">
            <h2>📝 Detalles del Request</h2>
            <div id="requestDetail"></div>
        </div>
    </div>

    <!-- Modal Nueva Regla -->
    <div class="modal" id="ruleModal">
        <div class="modal-content">
            <h2 style="margin-bottom: 20px; color: #667eea;">Nueva Regla de Modificación</h2>
            <form id="ruleForm">
                <div class="form-group">
                    <label>Nombre de la Regla</label>
                    <input type="text" id="ruleName" placeholder="Ej: Modificar API Token" required>
                </div>
                <div class="form-group">
                    <label>Patrón URL</label>
                    <input type="text" id="rulePattern" placeholder="Ej: api.example.com" required>
                </div>
                <div class="form-group">
                    <label>Tipo de Match</label>
                    <select id="ruleMatchType">
                        <option value="CONTAINS">Contiene</option>
                        <option value="EXACT">Exacto</option>
                        <option value="STARTS_WITH">Empieza con</option>
                        <option value="ENDS_WITH">Termina con</option>
                        <option value="REGEX">RegEx</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>Acción</label>
                    <select id="ruleAction">
                        <option value="MODIFY">Modificar</option>
                        <option value="BLOCK">Bloquear</option>
                        <option value="ALLOW">Permitir</option>
                    </select>
                </div>
                <div class="form-group" id="modifySection">
                    <label>Headers a Modificar (JSON)</label>
                    <textarea id="ruleHeaders" placeholder='{"Authorization": "Bearer NEW_TOKEN"}'></textarea>
                    
                    <label style="margin-top: 10px;">Body de Reemplazo (opcional)</label>
                    <textarea id="ruleBody" placeholder='{"modified": true}'></textarea>
                </div>
                <div style="display: flex; gap: 10px; margin-top: 20px;">
                    <button type="submit" class="btn btn-success" style="flex: 1;">Crear Regla</button>
                    <button type="button" class="btn btn-danger" onclick="closeRuleModal()" style="flex: 1;">Cancelar</button>
                </div>
            </form>
        </div>
    </div>

    <script>
        let requests = [];
        let rules = [];

        function loadRequests() {
            fetch('/api/requests')
                .then(r => r.json())
                .then(data => {
                    requests = data;
                    renderRequests();
                });
        }

        function loadRules() {
            fetch('/api/rules')
                .then(r => r.json())
                .then(data => {
                    rules = data;
                    renderRules();
                });
        }

        function renderRequests() {
            const list = document.getElementById('requestList');
            if (requests.length === 0) {
                list.innerHTML = '<p style="color: #9ca3af; text-align: center; padding: 20px;">No hay requests capturados</p>';
                return;
            }
            
            list.innerHTML = requests.slice(0, 50).map(req => {
                const statusClass = req.response ? `status-$${Math.floor(req.response.statusCode / 100)}xx` : '';
                const statusCode = req.response ? req.response.statusCode : '...';
                return `
                    <div class="request-item" onclick="showRequestDetail(${req.id})">
                        <div>
                            <span class="method ${req.method}">${req.method}</span>
                            <span class="status-code ${statusClass}">${statusCode}</span>
                            ${req.modified ? '<span class="badge badge-warning">MODIFICADO</span>' : ''}
                        </div>
                        <span class="url">${req.url}</span>
                        <small style="color: #9ca3af;">${new Date(req.timestamp).toLocaleTimeString()}</small>
                    </div>
                `;
            }).join('');
        }

        function renderRules() {
            const list = document.getElementById('ruleList');
            if (rules.length === 0) {
                list.innerHTML = '<p style="color: #9ca3af; text-align: center; padding: 20px;">No hay reglas configuradas</p>';
                return;
            }
            
            list.innerHTML = rules.map(rule => `
                <div class="rule-item ${rule.enabled ? 'rule-enabled' : 'rule-disabled'}">
                    <div>
                        <strong>${rule.name}</strong>
                        <br>
                        <small style="color: #6b7280;">${rule.urlPattern}</small>
                        <br>
                        <span class="badge badge-info">${rule.matchType}</span>
                        <span class="badge ${rule.action === 'BLOCK' ? 'badge-warning' : 'badge-success'}">${rule.action}</span>
                    </div>
                    <button class="btn btn-danger btn-sm" onclick="deleteRule(${rule.id})">Eliminar</button>
                </div>
            `).join('');
        }

        function showRequestDetail(id) {
            const req = requests.find(r => r.id === id);
            if (!req) return;
            
            const detail = document.getElementById('requestDetail');
            const panel = document.getElementById('detailPanel');
            
            detail.innerHTML = `
                <h3>${req.method} ${req.url}</h3>
                <p><strong>Host:</strong> ${req.host}</p>
                <p><strong>Timestamp:</strong> ${new Date(req.timestamp).toLocaleString()}</p>
                
                <h4 style="margin-top: 20px;">Request Headers</h4>
                <pre>${JSON.stringify(req.headers, null, 2)}</pre>
                
                ${req.body ? `
                    <h4 style="margin-top: 20px;">Request Body</h4>
                    <pre>${req.body}</pre>
                ` : ''}
                
                ${req.response ? `
                    <h4 style="margin-top: 20px;">Response Status</h4>
                    <p><strong>${req.response.statusCode} ${req.response.statusMessage}</strong></p>
                    
                    <h4 style="margin-top: 20px;">Response Headers</h4>
                    <pre>${JSON.stringify(req.response.headers, null, 2)}</pre>
                    
                    ${req.response.body ? `
                        <h4 style="margin-top: 20px;">Response Body</h4>
                        <pre>${req.response.body.substring(0, 1000)}${req.response.body.length > 1000 ? '...' : ''}</pre>
                    ` : ''}
                ` : '<p style="color: #f59e0b;">Esperando respuesta...</p>'}
            `;
            
            panel.style.display = 'block';
            panel.scrollIntoView({ behavior: 'smooth' });
        }

        function showRuleModal() {
            document.getElementById('ruleModal').style.display = 'flex';
        }

        function closeRuleModal() {
            document.getElementById('ruleModal').style.display = 'none';
            document.getElementById('ruleForm').reset();
        }

        document.getElementById('ruleForm').onsubmit = function(e) {
            e.preventDefault();
            
            const headers = document.getElementById('ruleHeaders').value;
            const removeHeaders = document.getElementById('removeHeaders').value;
            const searchReplaceHeaders = document.getElementById('searchReplaceHeaders').value;
            const body = document.getElementById('ruleBody').value;
            const searchReplaceBody = document.getElementById('searchReplaceBody').value;
            
            const modifyRequest = {};
            
            if (headers) {
                try {
                    modifyRequest.modifyHeaders = JSON.parse(headers);
                } catch (e) {
                    alert('Error en formato JSON de headers: ' + e.message);
                    return;
                }
            }
            
            if (removeHeaders) {
                modifyRequest.removeHeaders = removeHeaders.split(',').map(h => h.trim()).filter(h => h);
            }
            
            if (searchReplaceHeaders) {
                try {
                    modifyRequest.searchReplaceHeaders = JSON.parse(searchReplaceHeaders);
                } catch (e) {
                    alert('Error en JSON de buscar/reemplazar headers: ' + e.message);
                    return;
                }
            }
            
            if (body) {
                modifyRequest.replaceBody = body;
            }
            
            if (searchReplaceBody) {
                try {
                    modifyRequest.searchReplaceBody = JSON.parse(searchReplaceBody);
                } catch (e) {
                    alert('Error en JSON de buscar/reemplazar body: ' + e.message);
                    return;
                }
            }
            
            const rule = {
                id: Date.now(),
                enabled: true,
                name: document.getElementById('ruleName').value,
                urlPattern: document.getElementById('rulePattern').value,
                matchType: document.getElementById('ruleMatchType').value,
                action: document.getElementById('ruleAction').value,
                modifyRequest: Object.keys(modifyRequest).length > 0 ? modifyRequest : null
            };
            
            fetch('/api/rules', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(rule)
            }).then(() => {
                closeRuleModal();
                loadRules();
            });
        };

        function deleteRule(id) {
            if (confirm('¿Eliminar esta regla?')) {
                fetch(`/api/rules/$${id}`, {method: 'DELETE'})
                    .then(() => loadRules());
            }
        }

        function clearRequests() {
            if (confirm('¿Limpiar todos los requests?')) {
                fetch('/api/clear', {method: 'POST'})
                    .then(() => {
                        requests = [];
                        renderRequests();
                    });
            }
        }

        // Auto-refresh cada 2 segundos
        setInterval(() => {
            loadRequests();
        }, 2000);

        // Cargar datos iniciales
        loadRequests();
        loadRules();
    </script>
</body>
</html>
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
    
    private fun serveRequests(): Response {
        val requests = proxyService.getRequests()
        val json = gson.toJson(requests)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
    
    private fun serveRules(): Response {
        val rules = proxyService.getRules()
        val json = gson.toJson(rules)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
    
    private fun createRule(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No body")
        
        try {
            val rule = gson.fromJson(body, ProxyRule::class.java)
            proxyService.addRule(rule)
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", e.message)
        }
    }
    
    private fun deleteRule(uri: String): Response {
        val id = uri.substringAfterLast("/").toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid ID")
        
        proxyService.deleteRule(id)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
    }
    
    private fun updateRule(uri: String, session: IHTTPSession): Response {
        val id = uri.substringAfterLast("/").toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid ID")
        
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No body")
        
        try {
            val rule = gson.fromJson(body, ProxyRule::class.java)
            proxyService.updateRule(id, rule)
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", e.message)
        }
    }
    
    private fun serveProxyStatus(): Response {
        val status = mapOf(
            "running" to proxyService.isProxyRunning(),
            "port" to 2580,
            "requestCount" to proxyService.getRequests().size
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
    }
    
    private fun clearRequests(): Response {
        proxyService.clearRequests()
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
    }
    
    private fun serveStaticFile(uri: String): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }
    
    companion object {
        private const val TAG = "WebServerUI"
    }
}
