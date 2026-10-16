(ns kmet.app.ui.test-suspend
  "Suspend-to-background (ctrl+z / app.suspend): the TUI hands the terminal
   back before the process group stops and reclaims it when the shell
   resumes us; Windows only reports that it cannot stop (pi: handleCtrlZ).
   The stop itself is stubbed — a test that really sent SIGTSTP to its own
   process group would suspend the test runner."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.suspend :as suspend]
            [kmet.libs.process :as process]
            [kmet.tui.core :as tui]))

(deftest test-suspend-releases-the-terminal-around-the-stop
  (testing "tui-suspend! runs before the stop and tui-resume! after the
            SIGCONT-parked suspend-to-background! returns"
    (let [events (atom [])]
      (with-redefs [process/windows-os? false
                    tui/tui-suspend! (fn [_] (swap! events conj :suspend))
                    tui/tui-resume! (fn [_] (swap! events conj :resume))
                    process/suspend-to-background! (fn [] (swap! events conj :stop))]
        (suspend/handle-suspend {:tui ::tui}))
      (is (= [:suspend :stop :resume] @events)))))

(deftest test-suspend-resumes-after-a-failed-stop
  (testing "a failing stop (e.g. no kill binary) still reclaims the terminal"
    (let [events (atom [])]
      (with-redefs [process/windows-os? false
                    tui/tui-suspend! (fn [_] (swap! events conj :suspend))
                    tui/tui-resume! (fn [_] (swap! events conj :resume))
                    process/suspend-to-background!
                    (fn []
                      (swap! events conj :stop)
                      (throw (ex-info "no kill" {})))]
        (is (thrown? Exception (suspend/handle-suspend {:tui ::tui})))
        (is (= [:suspend :stop :resume] @events))))))

(deftest test-suspend-windows-reports-unsupported
  (testing "Windows has no job-control stop: a status message only, no TUI
            suspend and no signal"
    (let [messages (atom [])]
      (with-redefs [process/windows-os? true
                    chat-history/chat-history-show-status!
                    (fn [_ch message] (swap! messages conj message))
                    tui/tui-suspend! (fn [_] (throw (ex-info "must not suspend" {})))
                    process/suspend-to-background!
                    (fn [] (throw (ex-info "must not signal" {})))]
        (suspend/handle-suspend {:tui ::tui :chat-history ::chat}))
      (is (= ["Suspend to background is not supported on Windows"] @messages)))))
