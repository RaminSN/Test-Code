(import '[java.time LocalDateTime OffsetDateTime ZoneOffset]
        '[java.time.format DateTimeFormatter])

;; -------------------
;; T5 Server-data
;; -------------------

(def t5-data
  (-> "output/duplicated-nvv-reports.json"
      slurp
      (json/parse-string true)))

(defn report-type
  "Härleder anteckningstyp baserat på vilka nycklar som finns i datastrukturen."
  [report]
  (condp #(every? %2 %1) (set (keys report))
    [:Uppkomstplats
     :CfarNR]
    :Transportplanering

    [:TransportStartDatum
     :TransportStartplats
     :TransportSlutplats]
    :Transport

    [:KommandeHanteringsPlats
     :SenasteHanteringsPlats]
    :InsamlingsMottagning

    [:BehandlingsPlats
     :SenasteHanteringsplats]
    :Behandlingsmottagning
    
    :InsamnlingsTransportOrBehandlingsoverlamning))

(def t5-reports-xform
  "Transformerar den stora trädstrukturen i duplicated-nvv-reports.json
  genom att platta ut den till en simpel lista och modifiera rapporterna genom att
  ersätta den svårlästa 'hash' med en mer användarvänlig 'report-type'."  
  (comp
   (mapcat vals)
   (mapcat vals)
   (map (comp
         #(update % :payload dissoc :hash)
         #(assoc % :report-type (report-type (:payload %)))))))

(def t5-reports
  "Anteckningarna från T5."
  (into [] t5-reports-xform [t5-data]))

;; ---------------
;; NVV data
;; ---------------

(def nvv-data
  (-> "nvv-data.csv"
      slurp
      (csv/read-csv :separator \;)))



(defn process-locations
  "Bearbetar platsinformationen från jobbiga dynamiska nycklar till en statisk struktur
  som är enklare och mer bekant att jobba med."
  [report]
  (let [location-keys (filter #(str/starts-with? (name %) "Plats") (keys report))
        grouped-keys  (group-by #(second (re-find #"^(Plats\d+)\." (name %))) location-keys)]
    (reduce (fn [m [_ keys]]
              (let [sub    (into {} (map (fn [k] [(keyword (last (str/split (name k) #"\.")))
                                               (get report k)])
                                      keys))
                    type   (keyword (:Typ sub))
                    fields (dissoc sub :Typ)]
                (assoc m type fields)))
            (apply dissoc report location-keys)
            grouped-keys)))

(def nvv-reports
  "Anteckningarna från NVV.

  Transformerar anteckningarna från NVVs CSV-fil till något mer 'vänligt'
  genom att konvertera rubrikerna samt bearbeta platserna till en struktur
  som liknar den riktiga payload-strukturen."
  (let [headers (map keyword (first nvv-data))]
    (into [] (comp
              (drop 1)
              (map #(zipmap headers %))
              (map process-locations))
          nvv-data)))

;; ---------------
;; Main
;; ---------------

(defn parse-time
  "Läser in tidpunkterna från de två olika formaten till
  en gemensam java.time.Instant.

  Tidpunkterna från NVV har följande format:
      yyyy-MM-dd HH:mm:ss.SSSSSSS.

  Tidpunkterna från T5 Server-loggarna har följande format: 
      yyyy-MM-ddTHH:mm:ss.SSSSSSS+02:00"
  [t]
  (if (str/includes? t "T")
    (.toInstant (OffsetDateTime/parse t))
    (.toInstant (.atOffset
                 (LocalDateTime/parse t (DateTimeFormatter/ofPattern
                                         "yyyy-MM-dd HH:mm:ss.SSSSSSS"))
                 (ZoneOffset/of "+02:00")))))

(defn group
  "Utility för sub-mappningar. Används för plats och kontakt som är repetitivt i anteckningarna."
  ([mapping] {::group mapping})
  ([key mapping] {::group mapping ::key key}))

(defn normalize
  "Transformerar anteckning enligt angiven mappning. Syftet är att översätta de olika
  anteckningarna till en gemensam struktur."
  [mapping report]
  (reduce-kv (fn [m k v]
               (assoc m k (cond
                            (::group v) (normalize (::group v)
                                                   (if (::key v)
                                                     (get report (::key v))
                                                     report))
                            (vector? v) (get-in report v)
                            :else       (get report v))))
             {}
             mapping))

(defn contact-mapping [epost-key namn-key tel-key]
  (group {:epost epost-key
          :namn  namn-key
          :tel   tel-key}))

(defn t5-location-mapping [key]
  (group key {:kommunkod :Kommunkod
              :adress    [:Adress :Adressrad]
              :postnr    [:Adress :Postnummer]}))


(def t5-mapping
  "Översättning mellan strukturerna i t5-reports och den gemensamma modellen."
  {
   :avfall/kod               [:Avfall :Kod]
   :avfall/mangd             [:Avfall :Mangd]
   :behandlingsplats         (t5-location-mapping :BehandlingsPlats)
   :cfarnr                   :CfarNR
   :kommande-hanteringsplats (t5-location-mapping :KommandeHanteringsPlats)
   :mottagningsdatum         :MottagningsDatum
   :ombud/beskrivning        :Ombud
   :ombud/kontakt            (contact-mapping :OmbudetsKontaktpersonEpost
                                              :OmbudetsKontaktpersonNamn
                                              :OmbudetsKontaktpersonTelefonnummer)
   :ombud/namn               :OmbudetsNamn
   :referens                 :Referens
   :senaste-hanteringsplats  (t5-location-mapping :SenasteHanteringsplats)
   :tidigare-innehavare      :TidigareInnehavare
   :tidpunkt                 :Tidpunkt
   :transport/start-datum    :TransportStartDatum
   :transport/start-plats    (t5-location-mapping :TransportStartplats)
   :transport/slut-plats     (t5-location-mapping :TransportSlutplats)
   :uppkomstplats            (t5-location-mapping :Uppkomstplats)   
   :verksamhet/kontakt       (contact-mapping :VerksamhetensKontaktpersonEpost
                                              :VerksamhetensKontaktpersonNamn
                                              :VerksamhetensKontaktpersonTelefonnummer)
   :verksamhet/namn          :VerksamhetensNamn
   :verksamhet/utovare       :Verksamhetsutovare})

(defn nvv-location-mapping [key]
  (group key {:kommunkod :Kommunkod
              :adress    :Adressrad
              :postnr    :Postnummer}))

(def nvv-mapping
  "Översättning mellan strukturerna i nvv-reports och den gemensamma modellen."
  {
   :avfall/kod              :Avfall.Kod
   :avfall/mangd            :Avfall.Mangd
   :behandlingsplats        (nvv-location-mapping :Behandlingsplats)
   :cfarnr                  :CfarNr
   :kommande-hanteringsplats ;; SAKNAS
   :mottagningsdatum        :TransaktionsDatum
   :ombud/beskrivning       :Ombud
   :ombud/kontakt           (contact-mapping :OmbudetsKontaktpersonEpost
                                             :OmbudetsKontaktpersonNamn
                                             :OmbudetsKontaktpersonTelefonnummer)
   :ombud/namn              :OmbudetsNamn
   :referens                :Referens
   :senaste-hanteringsplats (nvv-location-mapping :SenasteHanteringsplats)
   :tidigare-innehavare     ;; SAKNAS
   :tidpunkt                :Tidpunkt
   :transport/start-datum   ;; SAKNAS
   :transport/start-plats   ;; SAKNAS
   :transport/slut-plats    ;; SAKNAS
   :uppkomstplats           ;; SAKNAS
   :verksamhet/kontakt      (contact-mapping :VerksamhetensKontaktpersonEpost
                                             :VerksamhetensKontaktpersonNamn
                                             :VerksamhetensKontaktpersonTelefonnummer)
   :verksamhet/namn         :VerksamhetensNamn
   :verksamhet/utovare      :Verksamhetsutovare})

(def processed-t5-reports
  (->>
   t5-reports
   (map :payload)
   (map (partial normalize t5-mapping))
   (map #(update % :tidpunkt parse-time))))

(def normalized-nvv-reports
  (map (partial normalize nvv-mapping) t5-reports))

;; ----------------------------------------------------------------------------------------------
;;
;; TODO
;;
;; 1. Finish nvv-mapping once NVV has given us an updated file.
;;
;; 2. Convert t5-reports and nvv-reports to common structure by applying the logic in
;;    t5-mapping and nvv-mapping. This is already implemented in normalize.
;;
;; 3. Match identical reports from t5 and nvv and assign AvfallId from nvv-report to t5-report.
;;
;; 4. Produce a file (e.g.CSV or JSON) with all the duplicated reports and their AvfallId.
;;
;; P.S.
;; Use Claude to help you write code if necessary. AI is significantly more efficacious
;; in Clojure than in more verbose and complicated languages like C#.
;;
;; P.P.S
;; Use Claude to help you with installing Babashka and running the program. It is just as simple
;; as running a NodeJS app.
;;
;; ----------------------------------------------------------------------------------------------

;; Comment används för smidig REPL-driven utveckling och debugging. Denna kod exekveras
;; inte vid körning av programmet. Ignorera.
(comment 
  (->
   (first processed-t5-reports)
   (#(into (sorted-map) %))
   pprint)

  (pprint (into (sorted-map) (first nvv-reports)))
  (pprint (into (sorted-map) (:payload (first t5-reports)))))
