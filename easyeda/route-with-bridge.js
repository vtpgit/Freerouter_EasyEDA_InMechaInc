/*
 * Copyright (C) 2026 Valentine Petrov, INMECHA INC. License: GNU GPL version 3 or later. See LICENSE and NOTICE.md.
 *
 * EasyEDA Standard script: route the open PCB with the EasyEDA to Freerouting bridge.
 *
 * Use:
 *   1. Start the bridge (run.bat or run.sh). Keep its window open.
 *   2. Open the PCB in the EasyEDA Standard editor.
 *   3. Run this script in the script dialog of the editor (Advanced > Extensions).
 *   4. Wait. The result opens as a new PCB. The PCB that you had open does not change.
 *   5. In the new PCB: rebuild the copper areas (Shift+B) and run the design rule check.
 *
 * The bridge reads the pads, the copper areas and the rules from the PCB source.
 * The routing rules are in config/rules.json of the bridge.
 *
 * This program is free software under the GNU General Public License, version 3 or later.
 * It comes WITHOUT ANY WARRANTY.
 */
(function () {
    'use strict';

    var BRIDGE = 'ws://127.0.0.1:3579/router';
    // Time for the job in seconds. 0: the time of config/rules.json.
    var TIME_S = 0;

    var box = document.getElementById('frbridge-status');
    if (!box) {
        box = document.createElement('div');
        box.id = 'frbridge-status';
        box.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:99999;max-width:520px;max-height:60%;overflow:auto;' +
            'background:#fff;color:#111;border:1px solid #888;padding:10px 14px;font:13px/1.4 sans-serif;white-space:pre-wrap;' +
            'box-shadow:0 2px 8px rgba(0,0,0,.3)';
        document.body.appendChild(box);
    }
    var closer = null;
    function say(text, final) {
        box.textContent = 'Freerouting bridge\n' + text;
        if (final && !closer) {
            closer = document.createElement('div');
            closer.textContent = 'Click here to close this box.';
            closer.style.cssText = 'margin-top:8px;color:#06c;cursor:pointer';
            closer.onclick = function () { if (box.parentNode) { box.parentNode.removeChild(box); } };
        }
        if (final) { box.appendChild(closer); }
    }

    if (typeof api !== 'function') {
        say('This script must run inside the EasyEDA Standard editor.', true);
        return;
    }

    // The saved form of the document. The bridge reads this form.
    var source;
    try {
        source = api('getSource', {type: 'compress'});
    } catch (e) {
        say('The editor did not give the PCB source: ' + e, true);
        return;
    }
    var was_text = typeof source === 'string';
    var text = was_text ? source : JSON.stringify(source);
    var probe = null;
    try { probe = JSON.parse(text); } catch (e2) { probe = null; }
    if (!probe || !probe.head || Object.prototype.toString.call(probe.shape) !== '[object Array]') {
        say('The editor gave a source that the bridge cannot read.\n' +
            'Use the file method: save the PCB source to a file and run "route" of the bridge on it. See docs/USER_GUIDE.md.', true);
        return;
    }
    if (String(probe.head.docType) !== '3') {
        say('The open document is not a PCB. Open the PCB and run the script again.', true);
        return;
    }

    var finished = false;
    var socket;
    try {
        socket = new WebSocket(BRIDGE);
    } catch (e3) {
        say('The connection to the bridge failed: ' + e3, true);
        return;
    }
    say('Connecting to the bridge on ' + BRIDGE + ' ...');
    socket.onopen = function () {
        socket.send(JSON.stringify({a: 'routeFile', data: text, timeout: TIME_S}));
        say('Routing. The time is set in config/rules.json of the bridge.\nDo not close the editor.');
    };
    socket.onmessage = function (event) {
        var message;
        try { message = JSON.parse(event.data); } catch (e4) { return; }
        if (message.a === 'fileProgress') {
            say('Routing. Open connections now: ' + message.open + '.\nDo not close the editor.');
            return;
        }
        if (message.a !== 'fileResult') {
            return;
        }
        finished = true;
        try { socket.close(); } catch (e5) { /* nothing to do */ }
        if (message.complete !== 1) {
            say('No result. ' + (message.message || ''), true);
            return;
        }
        try {
            api('applySource', {source: was_text ? message.data : JSON.parse(message.data), createNew: true});
        } catch (e6) {
            say('The editor did not accept the result: ' + e6 + '\nThe result file is in the folder "jobs" of the bridge (result.json).', true);
            return;
        }
        say('Done. The result is in a new PCB.\nOpen connections: ' + message.open + ' (before: ' + message.openBefore + ').\n' +
            'Now rebuild the copper areas (Shift+B) and run the design rule check.\n\n' + (message.report || ''), true);
    };
    socket.onerror = function () {
        if (!finished) {
            say('The bridge does not answer on ' + BRIDGE + '.\nStart the bridge (run.bat or run.sh) and run the script again.', true);
        }
    };
    socket.onclose = function () {
        if (!finished) {
            finished = true;
            say('The connection to the bridge closed before a result came. See the window of the bridge.', true);
        }
    };
}());
