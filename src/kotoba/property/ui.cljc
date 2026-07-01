(ns kotoba.property.ui
  "Operator-facing console for a community real-estate-agency actor.

  Renders an HTML read-only panel of parcels, listings and leases (with
  term-overlap warnings), using kotoba-lang/html + css. Pure data → markup:
  no network. The governor gates listing/lease/disclosure; this view only
  observes."
  (:require [html.core :as html]
            [css.core :as css]
            [kotoba.property :as prop]))

(def ^:private sheet
  {:rules
   {"body" {:font-family "system-ui,-apple-system,sans-serif" :margin 0 :color "#1a1a1a" :background "#fafafa"}
    "header.bar" {:display :flex :align-items :center :gap 12 :padding "12px 20px" :background "#fff" :border-bottom "1px solid #e5e5e5"}
    "header.bar h1" {:font-size 18 :margin 0 :font-weight 600}
    "header.bar .badge" {:margin-left :auto :font-size 12 :color "#666"}
    "main" {:max-width 980 :margin "24px auto" :padding "0 20px"}
    ".card" {:background "#fff" :border "1px solid #e5e5e5" :border-radius 8 :padding 16 :margin-bottom 16}
    "h2" {:margin-top 0 :font-size 15}
    "table" {:width "100%" :border-collapse :collapse :font-size 14}
    "th, td" {:text-align :left :padding "8px 10px" :border-bottom "1px solid #f0f0f0"}
    "th" {:font-weight 600 :color "#555" :font-size 12 :text-transform :uppercase :letter-spacing "0.04em"}
    "td.amt" {:font-variant-numeric :tabular-nums :text-align :right}
    ".ok" {:color "#137a3f"}
    ".warn" {:color "#b25c00" :background "#fff8e1" :padding "2px 6px" :border-radius 4}
    ".err" {:color "#b3261e" :background "#fbe9e7" :padding "2px 6px" :border-radius 4}
    ".muted" {:color "#888"}}})

(defn- stylesheet [] (html/->html (css/style-node sheet)))

(defn- money [n currency] (str (or n 0) " " (or currency "USD")))

(defn- parcel-rows [parcels]
  (for [p parcels]
    [:tr [:td (:parcel/id p)]
     [:td (or (:parcel/address p) "—")]
     [:td.amt (or (:parcel/area-m2 p) "—")]
     [:td (name (or (:parcel/zoning p) :unspecified))]]))

(defn- listing-rows [listings]
  (for [l listings]
    [:tr [:td (:listing/id l)]
     [:td (:listing/parcel l)]
     [:td (name (:listing/type l))]
     [:td.amt (money (:listing/price l) (:listing/currency l))]
     [:td (or (:listing/agent l) "—")]]))

(defn- overlap-badge [lease others]
  (if (some #(prop/term-overlaps? lease %) others)
    [:span.warn "overlap"]
    [:span.ok "clear"]))

(defn- lease-rows [leases]
  (for [l leases]
    [:tr [:td (:lease/id l)]
     [:td (:lease/parcel l)]
     [:td (or (:lease/tenant l) "—")]
     [:td (or (:lease/landlord l) "—")]
     [:td.amt (money (:lease/rent l) (:lease/currency l))]
     [:td (str (:lease/start l) " → " (:lease/end l))]
     [:td (overlap-badge l (remove #{l} leases))]]))

(defn dashboard
  "Render a full HTML console for a real-estate-agency operator."
  [{:keys [parcels listings leases] :as ctx}]
  (html/->html
    [:html
     [:head [:meta {:charset "utf-8"}] [:title "cloud-itonami · real-estate"]
      [:hiccup/raw (stylesheet)]]
     [:body
      [:header.bar [:h1 "Real-Estate Agency — Operator Console"] [:span.badge "read-only · governor-gated"]]
      [:main
       (when (seq parcels)
         [:section.card [:h2 "Parcels"]
          [:table [:thead [:tr [:th "ID"] [:th "Address"] [:th.amt "Area (m²)"] [:th "Zoning"]]]
           [:tbody (parcel-rows parcels)]]])
       (when (seq listings)
         [:section.card [:h2 "Listings"]
          [:table [:thead [:tr [:th "ID"] [:th "Parcel"] [:th "Type"] [:th.amt "Price"] [:th "Agent"]]]
           [:tbody (listing-rows listings)]]])
       (when (seq leases)
         [:section.card [:h2 "Leases (term-overlap checked)"]
          [:table [:thead [:tr [:th "ID"] [:th "Parcel"] [:th "Tenant"] [:th "Landlord"] [:th.amt "Rent"] [:th "Term"] [:th "Overlap"]]]
           [:tbody (lease-rows leases)]]])]]]))
