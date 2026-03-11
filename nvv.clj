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
  (let [headers (map #(keyword (str/replace % "\uFEFF" "")) (first nvv-data))]
    (into [] (comp
              (drop 1)
              (map #(zipmap headers %))
              (map process-locations))
          nvv-data)))

;; ---------------
;; Main
;; ---------------

(defn parse-time [t]
    (when t
      (cond
        (str/includes? t "+")
        (.toInstant (OffsetDateTime/parse t))

        (str/includes? t "T")
        (.toInstant (.atOffset (LocalDateTime/parse t) (ZoneOffset/of "+02:00")))

        :else
        (.toInstant (.atOffset
                     (LocalDateTime/parse t (DateTimeFormatter/ofPattern
                                             "yyyy-MM-dd HH:mm:ss.SSSSSSS"))
                     (ZoneOffset/of "+02:00"))))))

(defn group
  "Utility för sub-mappningar. Används för plats och kontakt som är repetitivt i anteckningarna."
  ([mapping] {::group mapping})
  ([key mapping] {::group mapping ::key key}))

(defn normalize
  "Transformerar anteckning enligt angiven mappning. Syftet är att översätta de olika
  anteckningarna till en gemensam struktur. Komplicerad funktion då den är baserad på
  logik enligt ovanstående group-utility."
  [mapping report]
  (reduce-kv (fn [m k v]
               (if (nil? v)
                 m
                 (assoc m k (cond
                              (::group v) (normalize (::group v)
                                                     (if (::key v)
                                                       (get report (::key v))
                                                       report))
                              (vector? v) (get-in report v)
                              :else       (get report v)))))
             {}
             mapping))

(defn normalize-values
  "Normaliserar heterogena värden."
  [report]
  (->
   report
   (update :tidpunkt parse-time)
   (update :mottagningsdatum parse-time)
   (update :avfall/mangd #(if (string? %)
                            (parse-double %)
                            (double %)))
   (update :cfarnr {"NULL"   nil
                    "SAKNAS" nil})))

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
   :avfallId                 nil ;; Denna ska vara nil
   :behandlingsplats         (t5-location-mapping :BehandlingsPlats)
   :cfarnr                   :CfarNR
   :kommande-hanteringsplats nil ;;(t5-location-mapping :KommandeHanteringsPlats)
   :mottagningsdatum         :MottagningsDatum
   :ombud/beskrivning        :Ombud
   :ombud/kontakt            (contact-mapping :OmbudetsKontaktpersonEpost
                                              :OmbudetsKontaktpersonNamn
                                              :OmbudetsKontaktpersonTelefonnummer)
   :ombud/namn               :OmbudetsNamn
   :referens                 :Referens
   :senaste-hanteringsplats  (t5-location-mapping :SenasteHanteringsplats)
   :tidigare-innehavare      nil ;; :TidigareInnehavare
   :tidpunkt                 :Tidpunkt
   :transport/start-datum    nil ;;:TransportStartDatum
   :transport/start-plats    nil ;;(t5-location-mapping :TransportStartplats)
   :transport/slut-plats     nil ;;(t5-location-mapping :TransportSlutplats)
   :uppkomstplats            nil ;;(t5-location-mapping :Uppkomstplats)   
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
   :avfall/kod               :Avfall.Kod
   :avfall/mangd             :Avfall.Mangd
   :avfallId                 :AvfallId
   :behandlingsplats         (nvv-location-mapping :Behandlingsplats)
   :cfarnr                   :CfarNr
   :kommande-hanteringsplats nil
   :mottagningsdatum         :TransaktionsDatum
   :ombud/beskrivning        :Ombud
   :ombud/kontakt            (contact-mapping :OmbudetsKontaktpersonEpost
                                              :OmbudetsKontaktpersonNamn
                                              :OmbudetsKontaktpersonTelefonnummer)
   :ombud/namn               :OmbudetsNamn
   :referens                 :Referens
   :senaste-hanteringsplats  (nvv-location-mapping :SenasteHanteringsplats)
   :tidigare-innehavare      nil
   :tidpunkt                 :Tidpunkt
   :transport/start-datum    nil
   :transport/start-plats    nil
   :transport/slut-plats     nil
   :uppkomstplats            nil
   :verksamhet/kontakt       (contact-mapping :VerksamhetensKontaktpersonEpost
                                              :VerksamhetensKontaktpersonNamn
                                              :VerksamhetensKontaktpersonTelefonnummer)
   :verksamhet/namn          :VerksamhetensNamn
   :verksamhet/utovare       :Verksamhetsutovare})


;; Normaliserar rapporterna till den gemensamma strukturen i två steg:
;;
;;  1. normalize transformerar strukturen.
;;  2. normalize-values konverterar värden som inte representeras likadant i de olika strukturerna.

(def processed-t5-reports
  (->>
   t5-reports
   (map #(update % :payload (partial normalize t5-mapping)))
   (map #(update % :payload normalize-values))))

(def processed-nvv-reports
  (->>
   nvv-reports
   (map (partial normalize nvv-mapping))
   (map normalize-values)))

(defn match-key
  "Exkluderar nycklar för matchning mellan rapporter."
  [report]
  (dissoc report :tidpunkt :avfallId))

(def nvv-index
  (group-by match-key processed-nvv-reports))

(defn find-nvv-report-matches
  "Hittar anteckningar från NVV smo matchar rapporten från T5."
  [t5-report]
  (get nvv-index ((comp match-key :payload) t5-report)))

(defn add-avfall-ids
  "Lägger till avfall-ID på en processed-t5-report."
  [report]
  (let [avfallIds (->>
                   (find-nvv-report-matches report)
                   (filter identity)
                   (map #(select-keys % [:avfallId :tidpunkt])))]
    (assoc report :avfallIds avfallIds)))

(def finished-reports
  (map add-avfall-ids processed-t5-reports))

(spit "test-output.json"
      (json/generate-string finished-reports
                            {:pretty true}))

(comment

  (->>
   finished-reports
   (filter (comp seq :avfallIds))
   first
   pprint)
  
  (pprint (first processed-t5-reports))

  (->> processed-nvv-reports
       first
       pprint)

  (->
   processed-nvv-reports
   first
   pprint))
