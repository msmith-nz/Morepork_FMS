<?php

function getCurrentUser() {
    if (isset($_COOKIE['session'])) {
        $flask_session = $_COOKIE['session'];
        $user_data = validateFlaskSession($flask_session);
        
        if ($user_data && $user_data['role'] === 'admin') {
            $_SESSION['user'] = $user_data;
            $_SESSION['login_time'] = time();
            return $user_data;
        } else {
            if (isset($_SESSION['user'])) {
                destroyUserSession();
                logSecurityEvent('session_invalidated', [
                    'reason' => $user_data ? 'insufficient_permissions' : 'flask_session_invalid',
                    'user_role' => $user_data['role'] ?? 'unknown'
                ]);
            }
            return null;
        }
    } else {
        if (isset($_SESSION['user'])) {
            destroyUserSession();
            logSecurityEvent('session_invalidated', ['reason' => 'no_flask_session']);
        }
        return null;
    }
}

// To-do

?>