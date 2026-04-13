<?php
// ─────────────────────────────────────────────────────────────────────────────
//  WebMail — communicates exclusively with the REST API (localhost:8080)
//  No direct database access. Session holds the Bearer token from the API.
// ─────────────────────────────────────────────────────────────────────────────
session_start();

define('API', 'http://localhost:8080/api');

// ── HTTP helpers ──────────────────────────────────────────────────────────────
function api(string $method, string $path, array $body = [], string $token = ''): array {
    $ch = curl_init(API . $path);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST  => $method,
        CURLOPT_HTTPHEADER     => array_filter([
            'Content-Type: application/json',
            $token ? "Authorization: Bearer $token" : '',
        ]),
        CURLOPT_POSTFIELDS     => $body ? json_encode($body) : null,
        CURLOPT_TIMEOUT        => 8,
    ]);
    $raw  = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return ['code' => $code, 'data' => json_decode($raw, true) ?? []];
}

// ── Auth helpers ──────────────────────────────────────────────────────────────
function authed(): bool   { return !empty($_SESSION['token']); }
function token(): string  { return $_SESSION['token'] ?? ''; }
function uname(): string  { return $_SESSION['username'] ?? ''; }

function requireAuth(): void {
    if (!authed()) { header('Location: ?page=login'); exit; }
}

// ── Action router ─────────────────────────────────────────────────────────────
$page   = $_GET['page']   ?? 'inbox';
$action = $_POST['action'] ?? '';
$error  = '';
$ok     = '';

if ($action === 'login') {
    $r = api('POST', '/login', [
        'username' => trim($_POST['username'] ?? ''),
        'password' => trim($_POST['password'] ?? ''),
    ]);
    if ($r['code'] === 200 && !empty($r['data']['token'])) {
        $_SESSION['token']    = $r['data']['token'];
        $_SESSION['username'] = $r['data']['username'];
        header('Location: ?page=inbox'); exit;
    }
    $error = 'Identifiants invalides.';
    $page  = 'login';
}

if ($action === 'logout') {
    api('POST', '/logout', [], token());
    session_destroy();
    header('Location: ?page=login'); exit;
}

if ($action === 'send') {
    requireAuth();
    $r = api('POST', '/emails', [
        'to'      => trim($_POST['to']      ?? ''),
        'subject' => trim($_POST['subject'] ?? ''),
        'content' => trim($_POST['content'] ?? ''),
    ], token());
    $ok    = $r['code'] === 201 ? 'Message envoyé.' : ($r['data']['error'] ?? 'Erreur.');
    $error = $r['code'] !== 201 ? $ok : '';
    $ok    = $r['code'] === 201 ? $ok : '';
    // Vider les champs POST après envoi réussi
    if ($r['code'] === 201) {
        $_POST['to']      = '';
        $_POST['subject'] = '';
        $_POST['content'] = '';
    }
    $page  = 'compose';
}

if ($action === 'delete') {
    requireAuth();
    $id = (int)($_POST['id'] ?? 0);
    api('DELETE', "/emails/$id", [], token());
    header('Location: ?page=inbox'); exit;
}

// ── Page data ─────────────────────────────────────────────────────────────────
$emails  = [];
$message = null;

if ($page === 'inbox' && authed()) {
    $r = api('GET', '/emails', [], token());
    $emails = array_reverse($r['data'] ?? []);   // newest first
}

if ($page === 'read' && authed()) {
    $id = (int)($_GET['id'] ?? 0);
    $r  = api('GET', "/emails/$id", [], token());
    $message = $r['data'] ?? null;
}

if (in_array($page, ['inbox','read','compose']) && !authed()) {
    header('Location: ?page=login'); exit;
}

// ── HTML helpers ──────────────────────────────────────────────────────────────
function h(string $s): string { return htmlspecialchars($s, ENT_QUOTES, 'UTF-8'); }
?>
<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>WebMail</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&family=DM+Mono:wght@300;400;500&display=swap" rel="stylesheet">
    <style>
        /* ── Reset & base ── */
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        :root {
            --ink:    #0f0e0c;
            --paper:  #f5f2eb;
            --cream:  #ede9df;
            --sand:   #d6d0c2;
            --rust:   #c0392b;
            --gold:   #b8860b;
            --dim:    #6b6458;
            --radius: 2px;
            --mono:   'DM Mono', monospace;
            --serif:  'DM Serif Display', Georgia, serif;
        }
        html, body { height: 100%; background: var(--paper); color: var(--ink); font-family: var(--mono); font-size: 14px; }

        /* ── Layout ── */
        .shell { display: grid; grid-template-rows: auto 1fr; min-height: 100vh; }

        /* ── Topbar ── */
        .topbar {
            display: flex; align-items: center; justify-content: space-between;
            padding: 0 2rem;
            height: 52px;
            background: var(--ink);
            color: var(--paper);
            letter-spacing: .08em;
            font-size: 12px;
        }
        .topbar-brand { font-family: var(--serif); font-size: 1.4rem; letter-spacing: .02em; color: var(--paper); }
        .topbar-user  { display: flex; align-items: center; gap: 1.5rem; }
        .topbar-user a, .topbar-user button {
            background: none; border: 1px solid rgba(255,255,255,.25);
            color: var(--paper); cursor: pointer; font-family: var(--mono);
            font-size: 11px; letter-spacing: .1em; padding: 5px 14px;
            text-decoration: none; transition: border-color .15s, background .15s;
        }
        .topbar-user a:hover, .topbar-user button:hover { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.6); }

        /* ── Main split ── */
        .main { display: grid; grid-template-columns: 200px 1fr; }

        /* ── Sidebar ── */
        .sidebar {
            border-right: 1px solid var(--sand);
            padding: 2rem 0;
            background: var(--cream);
        }
        .sidebar-label { font-size: 10px; letter-spacing: .15em; color: var(--dim); padding: 0 1.5rem 1rem; text-transform: uppercase; }
        .sidebar a {
            display: block; padding: .65rem 1.5rem;
            text-decoration: none; color: var(--ink); font-size: 13px; letter-spacing: .04em;
            border-left: 3px solid transparent; transition: background .1s, border-color .15s;
        }
        .sidebar a:hover { background: var(--sand); }
        .sidebar a.active { border-left-color: var(--rust); background: var(--sand); color: var(--rust); }

        /* ── Content pane ── */
        .pane { padding: 2.5rem 3rem; max-width: 860px; }

        /* ── Page headings ── */
        .pane h1 { font-family: var(--serif); font-size: 2rem; font-weight: 400; margin-bottom: 1.8rem; }

        /* ── Alerts ── */
        .alert { padding: .75rem 1.2rem; margin-bottom: 1.5rem; font-size: 12px; letter-spacing: .06em; border-left: 3px solid; }
        .alert-err { border-color: var(--rust); background: #f9ede9; color: var(--rust); }
        .alert-ok  { border-color: var(--gold); background: #faf6e8; color: var(--gold); }

        /* ── Login card ── */
        .login-wrap { display: flex; align-items: center; justify-content: center; min-height: 100vh; background: var(--paper); }
        .login-card {
            width: 380px; border: 1px solid var(--sand); padding: 3rem 2.5rem;
            background: var(--cream);
        }
        .login-card h1 { font-family: var(--serif); font-size: 2.2rem; font-weight: 400; margin-bottom: .4rem; }
        .login-card p  { color: var(--dim); font-size: 12px; letter-spacing: .06em; margin-bottom: 2rem; }

        /* ── Forms ── */
        .field { margin-bottom: 1.2rem; }
        .field label { display: block; font-size: 11px; letter-spacing: .12em; color: var(--dim); margin-bottom: .4rem; text-transform: uppercase; }
        .field input, .field textarea, .field select {
            width: 100%; background: var(--paper); border: 1px solid var(--sand);
            color: var(--ink); font-family: var(--mono); font-size: 13px;
            padding: .6rem .85rem; border-radius: var(--radius);
            transition: border-color .15s;
            outline: none;
        }
        .field input:focus, .field textarea:focus { border-color: var(--ink); }
        .field textarea { min-height: 160px; resize: vertical; }
        .btn {
            display: inline-block; cursor: pointer;
            font-family: var(--mono); font-size: 12px; letter-spacing: .12em;
            padding: .65rem 1.8rem; border: 1px solid var(--ink);
            background: var(--ink); color: var(--paper);
            text-decoration: none; transition: background .15s, color .15s;
        }
        .btn:hover { background: var(--paper); color: var(--ink); }
        .btn-ghost { background: transparent; color: var(--ink); }
        .btn-ghost:hover { background: var(--ink); color: var(--paper); }
        .btn-danger { border-color: var(--rust); background: var(--rust); color: #fff; }
        .btn-danger:hover { background: #fff; color: var(--rust); }

        /* ── Email list ── */
        .email-list { border-top: 1px solid var(--sand); }
        .email-row {
            display: grid; grid-template-columns: 180px 1fr auto;
            align-items: center; gap: 1.5rem;
            padding: .9rem 1rem; border-bottom: 1px solid var(--sand);
            text-decoration: none; color: var(--ink);
            transition: background .1s;
        }
        .email-row:hover { background: var(--cream); }
        .email-row.unread { background: #fffdf7; }
        .email-row.unread .email-from { font-weight: 500; }
        .email-row.unread .email-subject::before {
            content: '●'; color: var(--rust); margin-right: .5rem; font-size: 8px; vertical-align: middle;
        }
        .email-from    { font-size: 12px; color: var(--dim); letter-spacing: .04em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .email-subject { font-size: 13px; }
        .email-date    { font-size: 11px; color: var(--dim); white-space: nowrap; }
        .empty-state   { text-align: center; padding: 4rem 0; color: var(--dim); letter-spacing: .08em; font-size: 12px; }

        /* ── Message view ── */
        .msg-header { border-bottom: 1px solid var(--sand); padding-bottom: 1.5rem; margin-bottom: 2rem; }
        .msg-header h2 { font-family: var(--serif); font-size: 1.6rem; font-weight: 400; margin-bottom: 1rem; }
        .msg-meta { display: grid; gap: .4rem; }
        .msg-meta span { font-size: 12px; color: var(--dim); letter-spacing: .04em; }
        .msg-meta strong { color: var(--ink); }
        .msg-body { font-size: 14px; line-height: 1.9; white-space: pre-wrap; color: var(--ink); }
        .msg-actions { display: flex; gap: 1rem; margin-top: 2.5rem; padding-top: 1.5rem; border-top: 1px solid var(--sand); }

        /* ── Stats bar ── */
        .stats-bar { font-size: 11px; color: var(--dim); letter-spacing: .06em; margin-bottom: 1.5rem; }
    </style>
</head>
<body>

<?php if ($page === 'login'): ?>
<!-- ═══════════════════════════════════════ LOGIN ═══════════════════════════ -->
<div class="login-wrap">
    <div class="login-card">
        <h1>WebMail</h1>
        <p>Système de messagerie distribué</p>
        <?php if ($error): ?><div class="alert alert-err"><?= h($error) ?></div><?php endif ?>
        <form method="POST">
            <input type="hidden" name="action" value="login">
            <div class="field"><label>Utilisateur</label><input name="username" autofocus autocomplete="username" value="<?= h($_POST['username'] ?? '') ?>"></div>
            <div class="field"><label>Mot de passe</label><input name="password" type="password" autocomplete="current-password"></div>
            <button class="btn" style="width:100%;margin-top:.5rem">Connexion →</button>
        </form>
    </div>
</div>

<?php else: ?>
<!-- ═════════════════════════════════ AUTHENTICATED SHELL ═══════════════════ -->
<div class="shell">
    <!-- Topbar -->
    <header class="topbar">
        <span class="topbar-brand">WebMail</span>
        <div class="topbar-user">
            <span><?= h(uname()) ?></span>
            <a href="?page=compose">+ Nouveau</a>
            <form method="POST" style="margin:0"><input type="hidden" name="action" value="logout"><button>Déconnexion</button></form>
        </div>
    </header>

    <!-- Body -->
    <div class="main">
        <!-- Sidebar -->
        <nav class="sidebar">
            <div class="sidebar-label">Navigation</div>
            <a href="?page=inbox"   class="<?= $page==='inbox'   ? 'active' : '' ?>">▤ Boîte de réception</a>
            <a href="?page=compose" class="<?= $page==='compose' ? 'active' : '' ?>">✎ Nouveau message</a>
        </nav>

        <!-- Content -->
        <div class="pane">

            <?php if ($page === 'inbox'): ?>
            <!-- ── INBOX ──────────────────────────────────────────────────────── -->
            <h1>Boîte de réception</h1>
            <?php
            $total  = count($emails);
            $unread = count(array_filter($emails, fn($e) => !($e['seen'] ?? true)));
            ?>
            <div class="stats-bar"><?= $total ?> message<?= $total>1?'s':'' ?><?= $unread ? " — <strong>$unread non lu".($unread>1?'s':'')."</strong>" : '' ?></div>

            <div class="email-list">
                <?php if (empty($emails)): ?>
                <div class="empty-state">Aucun message dans votre boîte.</div>
                <?php else: foreach ($emails as $e):
                    $seen = $e['seen'] ?? false;
                    $from = $e['from'] ?? '';
                    $subj = $e['subject'] ?: '(sans objet)';
                    $date = substr($e['date'] ?? '', 0, 16);
                    $id   = (int)($e['id'] ?? 0);
                ?>
                <a class="email-row <?= !$seen ? 'unread' : '' ?>" href="?page=read&id=<?= $id ?>">
                    <span class="email-from"><?= h($from) ?></span>
                    <span class="email-subject"><?= h($subj) ?></span>
                    <span class="email-date"><?= h($date) ?></span>
                </a>
                <?php endforeach; endif ?>
            </div>

            <?php elseif ($page === 'read' && $message): ?>
            <!-- ── READ MESSAGE ───────────────────────────────────────────────── -->
            <h1>Message</h1>
            <div class="msg-header">
                <h2><?= h($message['subject'] ?: '(sans objet)') ?></h2>
                <div class="msg-meta">
                    <span><strong>De :</strong> <?= h($message['from'] ?? '') ?></span>
                    <span><strong>Date :</strong> <?= h(substr($message['date'] ?? '', 0, 19)) ?></span>
                    <span><strong>Statut :</strong> <?= ($message['seen'] ?? false) ? 'Lu' : 'Non lu' ?></span>
                </div>
            </div>
            <div class="msg-body"><?= h($message['content'] ?? '') ?></div>
            <div class="msg-actions">
                <a class="btn btn-ghost" href="?page=inbox">← Retour</a>
                <form method="POST">
                    <input type="hidden" name="action" value="delete">
                    <input type="hidden" name="id" value="<?= (int)($message['id'] ?? 0) ?>">
                    <button class="btn btn-danger" onclick="return confirm('Supprimer ce message ?')">Supprimer</button>
                </form>
            </div>

            <?php elseif ($page === 'compose'): ?>
            <!-- ── COMPOSE ────────────────────────────────────────────────────── -->
            <h1>Nouveau message</h1>
            <?php if ($error): ?><div class="alert alert-err"><?= h($error) ?></div><?php endif ?>
            <?php if ($ok):    ?><div class="alert alert-ok"><?= h($ok) ?></div><?php endif ?>
            <form method="POST">
                <input type="hidden" name="action" value="send">
                <div class="field"><label>À</label><input name="to" placeholder="destinataire@domaine.com" value="<?= h($_POST['to'] ?? '') ?>"></div>
                <div class="field"><label>Objet</label><input name="subject" value="<?= h($_POST['subject'] ?? '') ?>"></div>
                <div class="field"><label>Message</label><textarea name="content"><?= h($_POST['content'] ?? '') ?></textarea></div>
                <button class="btn">Envoyer →</button>
            </form>

            <?php elseif ($page === 'read' && !$message): ?>
            <div class="empty-state">Message introuvable.</div>
            <a class="btn btn-ghost" href="?page=inbox" style="margin-top:1rem">← Retour</a>
            <?php endif ?>

        </div><!-- /pane -->
    </div><!-- /main -->
</div><!-- /shell -->
<?php endif ?>

</body>
</html>