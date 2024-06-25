const mongoose = require("mongoose");

// Schema
const urlSchema = new mongoose.Schema({
    shortId: {
        type: String,
        required: true,
        unique: true,
    },
    redirectURL:{
        type: String,
        required: true,
    },
    totalClicks:{
        type: String,
        required: true,
    },
    visitHistory:[{ timestamp: { type: Number} }],
}, { timestamps:true }
);

// prepre Model
const URL = mongoose.model("url", urlSchema);

module.exports = URL;