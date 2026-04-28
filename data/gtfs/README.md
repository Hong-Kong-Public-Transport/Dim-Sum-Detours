# GTFS Feeds

Place static GTFS `.zip` files in this directory. The backend will discover them on startup.

Example:
```
data/gtfs/
├── README.md          (this file)
├── kcm-seattle.zip    (e.g. King County Metro)
└── tfl-london.zip
```

Recommended sources:
- [Mobility Database](https://mobilitydatabase.org/)
- [transit.land](https://www.transit.land/)

**Note:** `.zip` files are git-ignored. Don't commit transit feeds.
