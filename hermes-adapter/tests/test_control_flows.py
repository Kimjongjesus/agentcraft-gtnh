"""Decision classification, board writes, confirm tokens, chat and service/cron executors, end to end
over the socket with a fake `hermes` on the policy's program path (the real hermes is never called)."""

from __future__ import annotations

import json
import os
import time
import unittest
from pathlib import Path

from test_control_common import ACTOR, FIXTURE, OTHER_ACTOR, ControlCase, base_policy, control_client

from hermes_control import board as board_mod
from hermes_control import ledger as ledger_mod

TAG = "[from game: TestPlayer/00000000]"
FAKE_TOKEN = "sk-" + "ant-api03-" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"  # built at runtime: not a real key


def extra_board(env) -> board_mod.FixtureBoardReader:
    data = json.loads(FIXTURE.read_text())
    main = data["boards"]["main"]
    extra = [
        ("t-x1", "QUESTION q9: May I run the placeholder command? || CHOICES: Approve | Deny", "needs_input"),
        ("t-x2", "Please look at this placeholder thing || CHOICES: Yes | No", "needs_input"),
        ("t-x3", "\u200bPERMISSION p9: zero width prefix || CHOICES: Approve | Deny", "needs_input"),
        ("t-x4", "REVISE r1: placeholder revision || CHOICES: Revise | Accept", "needs_input"),
        ("t-x5", "QUESTION q5: is this a question? || CHOICES: A | B", "dispatch_hold"),
        ("t-x6", "QUESTION q6: Should we allow the placeholder cache? || CHOICES: Yes | No", "needs_input"),
        ("t-x7", "QUESTION q7: placeholder with a note || CHOICES: Alpha | Beta", "needs_input"),
    ]
    for cid, reason, kind in extra:
        main["cards"].append({"id": cid, "title": f"card {cid}", "status": "todo", "assignee": "builder-a"})
        main["decisions"].append({"id": f"d-main-{cid[-1]}00", "card": cid, "reason": reason, "blockKind": kind})
    path = env.root / "board2.json"
    path.write_text(json.dumps(data))
    return board_mod.FixtureBoardReader(path)


class DecisionTest(ControlCase):
    def board(self, env):
        return extra_board(env)

    def answer(self, c, card, dec, **kw):
        return self.env.ask(c, "decision.answer", {"card": card, "decision": dec, **kw})

    def test_permission_can_never_be_approved(self):
        c = self.env.client()
        for choice in ("Approve", "approve", "APPROVE", " Approve ", "Approve\u200b", "\uff21pprove", "Deny; Approve", "Deny or Approve", "deny",
                       "Yes", "Allow", "Approve\n", "\u0410pprove", "Approve (Deny)", "Deny\u0000", ""):
            r = self.answer(c, "t-demo-4", "d-main-103", choice=choice) if choice else self.answer(c, "t-demo-4", "d-main-103", text="Approve")
            self.assertEqual(r["status"], "refused", repr(choice))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_permission_note_cannot_smuggle_an_approve(self):
        c = self.env.client()
        r = self.answer(c, "t-demo-4", "d-main-103", choice="Approve", text="deny")
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_permission_deny_is_allowed_with_a_note(self):
        c = self.env.client()
        r = self.answer(c, "t-demo-4", "d-main-103", choice="Deny", text="not now")
        self.assertEqual(r["status"], "applied")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], ["kanban", "--board", "main", "unblock", f"--reason={TAG} Deny: not now", "t-demo-4"])

    def test_permission_by_prefix_with_zero_width_char_and_by_choices_and_by_word(self):
        c = self.env.client()
        for card, dec in (("t-x1", "d-main-100"), ("t-x3", "d-main-300"), ("t-x6", "d-main-600")):
            self.assertEqual(self.answer(c, card, dec, choice="Approve" if card != "t-x6" else "Yes")["status"], "refused", card)
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertEqual(self.answer(c, "t-x1", "d-main-100", choice="Deny")["status"], "applied")

    def test_handoff_is_read_only_by_default(self):
        c = self.env.client()
        for card, dec, choice in (("t-demo-5", "d-main-104", "Send to review"), ("t-demo-5", "d-main-104", "Revise"), ("t-x4", "d-main-400", "Accept")):
            r = self.answer(c, card, dec, choice=choice)
            self.assertEqual(r["status"], "refused", card)
            self.assertIn("hand-off", r["error"])
        self.assertEqual(self.env.hermes_calls(), [])

    def test_unknown_and_foreign_block_kinds_are_read_only(self):
        c = self.env.client()
        self.assertEqual(self.answer(c, "t-x2", "d-main-200", choice="Yes")["status"], "refused")
        r = self.answer(c, "t-x5", "d-main-500", choice="A")
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_question_choice_must_be_offered(self):
        c = self.env.client()
        for kw in ({"choice": "Option Z"}, {"choice": "option a"}, {"choice": "Option A "}, {"text": "Option A"}, {"choice": "Option A\u200b"}):
            self.assertEqual(self.answer(c, "t-demo-2", "d-main-101", **kw)["status"], "refused", kw)
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertEqual(self.answer(c, "t-demo-2", "d-main-101", choice="Option B", text="because")["status"], "applied")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], ["kanban", "--board", "main", "unblock", f"--reason={TAG} Option B: because", "t-demo-2"])

    def test_open_text_question_takes_text_only(self):
        c = self.env.client()
        self.assertEqual(self.answer(c, "t-demo-3", "d-main-102", choice="Anything")["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])
        r = self.answer(c, "t-demo-3", "d-main-102", text="--yolo --board other; rm -rf /")
        self.assertEqual(r["status"], "applied")
        [call] = self.env.hermes_calls()
        argv = call["argv"]
        self.assertEqual(argv[:4], ["kanban", "--board", "main", "unblock"])
        self.assertEqual(argv[-1], "t-demo-3")
        self.assertEqual(len(argv), 6)  # the game's text is one --reason=... element, nothing else
        self.assertTrue(argv[4].startswith("--reason=" + TAG))

    def test_decision_must_be_open_and_match_the_card(self):
        c = self.env.client()
        self.assertEqual(self.answer(c, "t-demo-2", "d-main-999", choice="Option A")["error"], "that decision is not open")
        self.assertEqual(self.answer(c, "t-demo-1", "d-main-101", choice="Option A")["error"], "that decision is not open")
        self.assertEqual(self.answer(c, "t-nope", "d-main-101", choice="Option A")["error"], "card not found on an allowed board")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_classification_ignores_request_text(self):
        # the game cannot turn a permission halt into a question by what it sends
        c = self.env.client()
        r = self.answer(c, "t-demo-4", "d-main-103", choice="Option A", text="QUESTION q1: this is only a question")
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])


class HandoffAnswerableTest(ControlCase):
    def policy(self, env):
        p = base_policy(env)
        p["capabilities"]["decision.answer"]["handoffAnswerable"] = True
        return p

    def test_handoff_answerable_when_the_owner_allows_it(self):
        c = self.env.client()
        r = self.env.ask(c, "decision.answer", {"card": "t-demo-5", "decision": "d-main-104", "choice": "Send to review"})
        self.assertEqual(r["status"], "applied")
        r = self.env.ask(c, "decision.answer", {"card": "t-demo-5", "decision": "d-main-104", "choice": "Ship it"})
        self.assertEqual(r["status"], "refused")
        # permission stays deny-only whatever the policy says
        r = self.env.ask(c, "decision.answer", {"card": "t-demo-4", "decision": "d-main-103", "choice": "Approve"})
        self.assertEqual(r["status"], "refused")


class BoardWriteTest(ControlCase):
    def test_card_create_is_unassigned_triage_tagged_and_idempotent(self):
        c = self.env.client()
        r = self.env.ask(c, "card.create", {"board": "main", "title": "A new placeholder card", "body": "- starts with a dash\nsecond line", "priority": 5}, id_="mk-1")
        self.assertEqual(r["status"], "applied")
        self.assertEqual(r["result"]["card"], "t_new1")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], [
            "kanban", "--board", "main", "create", "--triage", "--created-by=agentcraft-game", f"--idempotency-key=agentcraft-game:{ACTOR}:mk-1",
            "--priority=5", "--body-file", "-", "--json", f"{TAG} A new placeholder card"])
        self.assertTrue(call["stdin"].startswith(TAG + "\n\n- starts with a dash"))
        self.assertNotIn("--assignee", call["argv"])

    def test_titles_that_look_like_options_are_refused(self):
        c = self.env.client()
        for title in ("--yolo", "-n", "  -x", "\t-x"):
            self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": title})["status"], "refused", title)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_card_edit_comment_title_body_priority(self):
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "card.edit", {"card": "t-demo-1", "comment": "-looks like a flag"})["status"], "applied")
        self.assertEqual(self.env.ask(c, "card.edit", {"card": "t-demo-1", "title": "-new title", "priority": 3, "body": "b"})["status"], "applied")
        calls = self.env.hermes_calls()
        self.assertEqual(calls[0]["argv"], ["kanban", "--board", "main", "comment", "--author=agentcraft-game", "t-demo-1", f"{TAG} -looks like a flag"])
        self.assertEqual(calls[1]["argv"][:5], ["kanban", "--board", "main", "edit", f"--title={TAG} -new title"])
        self.assertEqual(calls[1]["argv"][-1], "t-demo-1")
        self.assertIn("--priority=3", calls[1]["argv"])

    def test_card_edit_only_when_not_running_but_comment_always(self):
        c = self.env.client()
        r = self.env.ask(c, "card.edit", {"card": "t-demo-6", "title": "new"})
        self.assertEqual((r["status"], r["error"]), ("refused", "card is running: only a comment is allowed"))
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertEqual(self.env.ask(c, "card.edit", {"card": "t-demo-6", "comment": "fyi"})["status"], "applied")

    def test_hermes_failure_is_refused_and_a_timeout_is_unknown(self):
        c = self.env.client()
        os.environ["FAKE_HERMES_MODE"] = "fail"
        r = self.env.ask(c, "card.create", {"board": "main", "title": "fails"})
        self.assertEqual(r["status"], "refused")
        self.assertIn("boom", r["error"])

    def test_unknown_when_the_command_times_out(self):
        from hermes_control import executors

        saved = executors.KANBAN_TIMEOUT
        executors.KANBAN_TIMEOUT = 1
        os.environ["FAKE_HERMES_MODE"] = "sleep"
        try:
            c = self.env.client()
            r = self.env.ask(c, "card.create", {"board": "main", "title": "slow"}, id_="slow-1")
            self.assertEqual(r["status"], "unknown")
            # and it is never re-run
            r2 = self.env.ask(c, "card.create", {"board": "main", "title": "slow"}, id_="slow-1")
            self.assertEqual(r2["status"], "unknown")
            self.assertEqual(len(self.env.hermes_calls()), 1)
        finally:
            executors.KANBAN_TIMEOUT = saved


class ConfirmTokenTest(ControlCase):
    ARGS = {"card": "t-demo-1", "board": "main", "profile": "builder-a"}

    def policy(self, env):
        p = base_policy(env)
        p["actors"] = [ACTOR, OTHER_ACTOR]
        return p

    def prompt(self, c=None, args=None):
        c = c or self.c
        return self.env.prompt(c, args or self.ARGS)

    def setUp(self):
        super().setUp()
        self.c = self.env.client()

    def confirm(self, c, token, rid, actor=None):
        c.send("action.confirm", {"actor": actor or c.actor, "token": token})
        return self.env.result_for(c, rid if rid else "x")

    def test_round_trip_assigns_the_card(self):
        rid, prompt, res = self.prompt()
        self.assertEqual(res["status"], "prompted")
        self.assertEqual(set(prompt["summary"]), {"card", "title", "board", "profile", "model", "body"})
        self.assertEqual(prompt["summary"]["title"], "Add the placeholder widget")
        self.assertLessEqual(prompt["expiresAt"] - int(time.time() * 1000), 60_000)
        self.assertEqual(self.env.hermes_calls(), [])  # nothing runs before the confirm
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        res = self.env.result_for(self.c, rid)
        self.assertEqual(res["status"], "applied")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], ["kanban", "--board", "main", "assign", "t-demo-1", "builder-a"])

    def test_summary_comes_from_the_board_not_the_request(self):
        rid, prompt, _ = self.prompt()
        self.assertEqual(prompt["summary"]["model"], "model-a")
        self.assertNotIn("TestPlayer", json.dumps(prompt["summary"]))

    def test_token_is_single_use(self):
        rid, prompt, _ = self.prompt()
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        self.assertEqual(self.env.result_for(self.c, rid)["status"], "applied")
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual((t, p["status"]), ("action.result", "refused"))
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_unknown_token(self):
        self.c.send("action.confirm", {"actor": self.c.actor, "token": "0" * 32})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_expired_token(self):
        rid, prompt, _ = self.prompt()
        self.env.ledger.db.execute("UPDATE tokens SET expires_ms = 1")
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual((p["status"], p["error"]), ("refused", "token expired"))
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertEqual(self.env.ledger.get_claim(ACTOR, rid)["state"], ledger_mod.REFUSED)

    def test_wrong_actor(self):
        rid, prompt, _ = self.prompt()
        other = control_client.ControlClient("127.0.0.1", self.env.port, self.env.key, OTHER_ACTOR, "Other", quiet=True)
        other.handshake()
        other.send("action.confirm", {"actor": other.actor, "token": prompt["token"]})
        t, p = other.recv(3)
        self.assertEqual(p["status"], "refused")
        # and the same connection with another actor field
        self.c.send("action.confirm", {"actor": {"uuid": OTHER_ACTOR, "name": "Other"}, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_card_revision_changed(self):
        rid, prompt, _ = self.prompt()
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["title"] = "Edited behind the prompt"
        card["revision"] = board_mod.revision(card)
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual((p["status"], p["error"]), ("refused", "card changed since the prompt"))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_card_started_running_after_the_prompt(self):
        rid, prompt, _ = self.prompt()
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["status"], card["run"] = "running", 9
        card["revision"] = board_mod.revision(card)
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_policy_reloaded_voids_tokens_and_bumps_the_revision(self):
        rid, prompt, _ = self.prompt()
        before = self.env.svc.revision
        self.env.write_policy(self.env.policy_dict)
        self.assertTrue(self.env.st.call(self.env.svc.reload_policy))
        self.assertNotEqual(self.env.svc.revision, before)
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 0)
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_reload_pushes_the_new_policy_to_clients(self):
        self.env.st.call(self.env.svc.reload_and_broadcast)
        seen = {}
        end = time.time() + 4
        while time.time() < end and "action.state" not in seen:
            t, p = self.c.recv(2)
            seen[t] = p
        self.assertTrue(seen["action.policy"]["revision"].endswith(".1"))

    def test_invalid_policy_on_reload_keeps_the_old_one(self):
        before = self.env.svc.revision
        bad = base_policy(self.env)
        bad["capabilities"]["host.reboot"] = {"enabled": True}
        self.env.write_policy(bad)
        self.assertFalse(self.env.st.call(self.env.svc.reload_policy))
        self.assertEqual(self.env.svc.revision, before)
        self.assertTrue(self.env.svc.policy.cap("card.create").enabled)
        self.assertTrue(any(a["event"] == "policy-reload-failed" for a in self.env.audit_lines()))

    def test_reload_that_disables_dispatch_refuses_the_confirm_even_without_a_token_void(self):
        rid, prompt, _ = self.prompt()
        p = base_policy(self.env)
        p["capabilities"]["card.dispatch"]["enabled"] = False
        self.env.write_policy(p)
        self.env.st.call(self.env.svc.reload_policy)
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, r = self.c.recv(3)
        self.assertEqual(r["status"], "refused")

    def test_lock_set_after_the_prompt(self):
        rid, prompt, _ = self.prompt()
        self.env.lock_path.write_text("{}")
        time.sleep(1.3)  # the lock poll voids tokens; the confirm re-checks the lock itself as well
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        end = time.time() + 3
        res = None
        while time.time() < end:
            t, p = self.c.recv(2)
            if t == "action.result":
                res = p
                break
        self.assertEqual(res["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_lock_set_right_before_confirm_without_waiting_for_the_poll(self):
        rid, prompt, _ = self.prompt()
        self.env.lock_path.write_text("{}")
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        end = time.time() + 3
        res = None
        while time.time() < end:
            t, p = self.c.recv(2)
            if t == "action.result":
                res = p
                break
        self.assertEqual(res["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_game_lock_voids_tokens(self):
        rid, prompt, _ = self.prompt()
        self.c.send("action.lock", {"actor": self.c.actor, "reason": "panic"})
        time.sleep(0.3)
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 0)
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        end = time.time() + 3
        res = None
        while time.time() < end:
            t, p = self.c.recv(2)
            if t == "action.result":
                res = p
                break
        self.assertEqual(res["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_cancelled_token(self):
        rid, prompt, _ = self.prompt()
        self.c.send("action.cancel", {"actor": self.c.actor, "token": prompt["token"]})
        res = self.env.result_for(self.c, rid)
        self.assertEqual(res["status"], "cancelled")
        self.c.send("action.confirm", {"actor": self.c.actor, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_cancel_by_another_actor_or_connection_does_nothing(self):
        rid, prompt, _ = self.prompt()
        self.c.send("action.cancel", {"actor": {"uuid": OTHER_ACTOR, "name": "Other"}, "token": prompt["token"]})
        t, p = self.c.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 1)

    def test_connection_closed_voids_the_token(self):
        rid, prompt, _ = self.prompt()
        self.c.close()
        time.sleep(0.3)
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 0)
        c2 = self.env.client()
        c2.send("action.confirm", {"actor": c2.actor, "token": prompt["token"]})
        t, p = c2.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_token_from_another_connection_is_refused(self):
        rid, prompt, _ = self.prompt()
        c2 = self.env.client()
        c2.send("action.confirm", {"actor": c2.actor, "token": prompt["token"]})
        t, p = c2.recv(3)
        self.assertEqual(p["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_tokens_are_stored_only_as_hashes(self):
        rid, prompt, _ = self.prompt()
        rows = self.env.ledger.db.execute("SELECT hash FROM tokens").fetchall()
        self.assertEqual(len(rows), 1)
        self.assertNotEqual(rows[0][0], prompt["token"])
        self.assertEqual(len(prompt["token"]), 32)

    def test_not_dispatchable_cards_get_no_prompt(self):
        for card in ("t-demo-6", "t-demo-7", "t-demo-2", "t-nope"):
            r = self.env.ask(self.c, "card.dispatch", {**self.ARGS, "card": card})
            self.assertEqual(r["status"], "refused", card)
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 0)

    def test_replay_of_a_prompted_request_returns_stored_state_without_a_new_token(self):
        rid, prompt, _ = self.prompt()
        r = self.env.ask(self.c, "card.dispatch", self.ARGS, id_=rid)
        self.assertEqual(r["status"], "prompted")
        self.assertEqual(self.env.ledger.open_tokens(ACTOR), 1)

    def test_too_many_open_confirms(self):
        board = self.env.board
        for i in range(6):
            card = {"id": f"t-open-{i}", "board": "main", "title": "x", "body": "", "status": "todo", "assignee": "", "priority": 0, "run": None,
                    "block": None, "model": "m", "revision": f"r{i}"}
            board.boards["main"]["cards"][card["id"]] = card
        results = [self.env.prompt(self.c, {**self.ARGS, "card": f"t-open-{i}"})[2]["status"] for i in range(6)]
        self.assertEqual(results.count("prompted"), 4)
        self.assertEqual(results.count("refused"), 2)


class ChatTest(ControlCase):
    def collect(self, c, rid, timeout=8):
        chat, end = [], time.time() + timeout
        while time.time() < end:
            t, p = c.recv(max(0.1, end - time.time()))
            if t == "action.chat":
                chat.append(p)
            if t == "action.result" and p["re"] == rid:
                return chat, p
        raise AssertionError("no result")

    def test_ask_runs_a_one_shot_with_the_policy_toolset_and_text_on_stdin(self):
        c = self.env.client()
        sent = c.request("agent.ask", {"agent": "helper-a", "text": "what is up --yolo"})
        chat, res = self.collect(c, sent["id"])
        self.assertEqual(res["status"], "applied")
        self.assertEqual(chat[-1]["final"], True)
        self.assertEqual(chat[0]["text"], "Hello from the fake agent.")
        self.assertEqual(chat[0]["agentId"], "helper-a")
        [call] = self.env.hermes_calls()
        argv = call["argv"]
        self.assertEqual(argv[:4], ["--profile", "helper-a", "chat", "--oneshot"])
        self.assertIn("what is up --yolo", call["stdin"])
        self.assertNotIn("what is up --yolo", " ".join(argv))
        self.assertEqual(argv[argv.index("--toolsets") + 1], "search")
        for bad in ("--yolo", "--resume", "-r", "--continue", "-c", "--accept-hooks", "--worktree"):
            self.assertNotIn(bad, argv)

    def test_chat_sends_conversation_and_agent_ids(self):
        c = self.env.client()
        sent = c.request("agent.chat", {"agent": "helper-a", "conversation": "conv-1", "text": "hi"})
        chat, res = self.collect(c, sent["id"])
        self.assertEqual(chat[0]["conversation"], "conv-1")
        self.assertEqual(res["status"], "applied")

    def test_reply_is_filtered_as_a_whole_even_across_frame_boundaries(self):
        os.environ["FAKE_HERMES_REPLY"] = ("word " * 355) + FAKE_TOKEN + "\n\nsecond paragraph at 192.0.2.7 with " + FAKE_TOKEN
        c = self.env.client()
        sent = c.request("agent.ask", {"agent": "helper-a", "text": "leak?"})
        chat, res = self.collect(c, sent["id"])
        joined = "\n\n".join(p["text"] for p in chat)
        self.assertNotIn("sk-ant", joined)
        self.assertNotIn("AbCdEfGh", joined)
        self.assertNotIn("192.0.2.7", joined)
        self.assertIn("[redacted]", joined)
        self.assertTrue(all(len(p["text"]) <= 2000 for p in chat))
        self.assertEqual([p["final"] for p in chat], [False] * (len(chat) - 1) + [True])

    def test_frames_are_complete_paragraphs(self):
        paras = [f"Paragraph {i}: " + ("lorem " * 120) for i in range(6)]
        os.environ["FAKE_HERMES_REPLY"] = "\n\n".join(paras)
        c = self.env.client()
        sent = c.request("agent.ask", {"agent": "helper-a", "text": "long"})
        chat, _ = self.collect(c, sent["id"])
        self.assertGreater(len(chat), 1)
        for p in chat:
            for part in p["text"].split("\n\n"):
                self.assertTrue(any(part.strip() == q.strip() for q in paras), "a frame cut a paragraph")

    def test_agent_not_in_policy_refused(self):
        c = self.env.client()
        r = self.env.ask(c, "agent.ask", {"agent": "root-shell", "text": "hi"})
        self.assertEqual((r["status"], r["error"]), ("refused", "agent not allowed"))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_chat_failure_is_refused(self):
        os.environ["FAKE_HERMES_MODE"] = "fail"
        c = self.env.client()
        r = self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": "hi"})
        self.assertEqual(r["status"], "refused")


class ChatDisabledByDefaultTest(ControlCase):
    def policy(self, env):
        return json.loads((Path(__file__).resolve().parent.parent / "hermes_control" / "policy.example.json").read_text())

    def test_example_policy_ships_chat_and_tier2_disabled(self):
        c = self.env.client()
        for cap, args in (("agent.chat", {"agent": "helper-a", "conversation": "c", "text": "hi"}), ("agent.ask", {"agent": "helper-a", "text": "hi"}),
                          ("card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"}), ("service.restart", {"service": "service-1"}),
                          ("cron.run", {"job": "job-a1"})):
            r = self.env.ask(c, cap, args)
            self.assertEqual((r["status"], r["error"]), ("refused", "capability disabled"), cap)


class ServiceAndCronTest(ControlCase):
    def test_restart_runs_exactly_the_policy_argv(self):
        c = self.env.client()
        r = self.env.ask(c, "service.restart", {"service": "service-1"})
        self.assertEqual(r["status"], "applied")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], ["restart-service-1"])

    def test_the_game_cannot_choose_the_argv_or_the_program(self):
        c = self.env.client()
        for name in ("service-1; reboot", "../service-1", "$(reboot)", "service-2"):
            r = self.env.ask(c, "service.restart", {"service": name})
            self.assertEqual(r["status"], "refused", name)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_no_shell_metacharacters_are_interpreted(self):
        marker = self.env.root / "pwned"
        p = base_policy(self.env)
        p["services"]["service-1"]["argv"] = [str(self.env.fake), f"; touch {marker}", "$(touch " + str(marker) + ")", "&& id"]
        self.env.write_policy(p)
        self.env.restart()
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "service.restart", {"service": "service-1"})["status"], "applied")
        self.assertFalse(marker.exists())
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"][0], f"; touch {marker}")
        self.assertEqual(call["argv"][2], "&& id")

    def test_cron_run_argv(self):
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-a1"})["status"], "applied")
        [call] = self.env.hermes_calls()
        self.assertEqual(call["argv"], ["cron", "run", "job-a1"])

    def test_missing_program_is_a_refusal_not_a_crash(self):
        p = base_policy(self.env)
        p["services"]["service-1"]["argv"] = ["/nonexistent/program-for-test"]
        self.env.write_policy(p)
        self.env.restart()
        c = self.env.client()
        r = self.env.ask(c, "service.restart", {"service": "service-1"})
        self.assertEqual(r["status"], "refused")

    def test_service_timeout_is_unknown(self):
        os.environ["FAKE_HERMES_MODE"] = "sleep"
        p = base_policy(self.env)
        p["services"]["service-1"]["timeoutSeconds"] = 1
        self.env.write_policy(p)
        self.env.restart()
        c = self.env.client()
        r = self.env.ask(c, "service.restart", {"service": "service-1"}, id_="svc-slow")
        self.assertEqual(r["status"], "unknown")
        again = self.env.ask(c, "service.restart", {"service": "service-1"}, id_="svc-slow")
        self.assertEqual(again["status"], "unknown")
        self.assertEqual(len(self.env.hermes_calls()), 1)


if __name__ == "__main__":
    unittest.main()
