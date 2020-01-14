# Views

Status: design

Views are snapshots of a database state according to a query.
They are stored as the resulting datoms and can be restored into a standalone Datascript database.
Views are incomplete by design, as a Datascript database by itself doesn't keep retraction history.

Views on a client can be kept up to date either from view or log updates.
In turn the view can be updated by any client with log access.
Since views are derived data there will always be a delay between the log update and the view update.

You can set security rules on each view, thus providing different access to different clients.
But updating a view requires log access, so it doesn't make much sense to give write access to a 
view if the client doesn't have at least read access to the log.

Views can be updated automatically or manually by setting the update policy.
Automatic updates are performed on any client on log update after a random delay and in batch.
The random delay and batch helps reduce view churn, and thus costs.
Firebase validation rules are used to discard updates from older database states, according to tx id, ensuring only the latest snapshot is written.
Views without automatic updates are updated by triggering a manual update via the API.
Manual updates are useful to keep a point-in-time view.

All links by default use a special view called `snapshot`.
The query for this view lists all datoms in the database, and is thus a snapshot of the whole database.
On link startup the connection will load the `snapshot` view and then apply newer transactions from the log.
This behaviour can be disabled in order to obtain a connection atom that has gone through all 
transactions, including retractions, but will be slower.


## Open questions & notes

- View usage requires using the tx ordering field for queries and storage.
  This is currently a server timestamp, which is of higher fidelity than a JS date.
  Need to validate the server timestamp can be used client-side.
- Should views also contain the result snapshot along with the datoms? 
  Clients that only consume the view, and not update it, might not care about the snapshot db.
- Updating a snapshot requires a DB whose state is consistent with the server, meaning all
  transactions must have been applied in the same order as in the server.
  But offline transactions might locally be out of order in relation to the server.
  Thus for snapshot updates there needs to be a resync step where the local db is synced to the 
  server order.
- A random delay in view update would help reduce snapshot churn, and thus costs and computation.
- On datom granularity it might be possible to provide view-based log query capability by keeping 
  track of entity membership on a query.
  Unsure if this would be useful given snapshots already exist.